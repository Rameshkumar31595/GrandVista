package com.grandvista.bookingservice.service.impl;

import com.grandvista.bookingservice.client.PaymentServiceClient;
import com.grandvista.bookingservice.client.RoomServiceClient;
import com.grandvista.bookingservice.client.dto.HoldRequest;
import com.grandvista.bookingservice.client.dto.HoldResult;
import com.grandvista.bookingservice.client.dto.PaymentCommand;
import com.grandvista.bookingservice.client.dto.PaymentResult;
import com.grandvista.bookingservice.config.BookingProperties;
import com.grandvista.bookingservice.dto.BookingResponse;
import com.grandvista.bookingservice.dto.CreateBookingRequest;
import com.grandvista.bookingservice.dto.PayBookingRequest;
import com.grandvista.bookingservice.dto.PaymentOutcomeResponse;
import com.grandvista.bookingservice.entity.Booking;
import com.grandvista.bookingservice.entity.BookingStatus;
import com.grandvista.bookingservice.exception.BusinessRuleException;
import com.grandvista.bookingservice.exception.DownstreamServiceException;
import com.grandvista.bookingservice.exception.ResourceNotFoundException;
import com.grandvista.bookingservice.repository.BookingRepository;
import com.grandvista.bookingservice.service.BookingService;
import com.grandvista.bookingservice.util.AuthenticatedUser;
import com.grandvista.bookingservice.util.StayDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Orchestrates the distributed booking workflow (an orchestration-based saga with persisted recovery states).
 * <p>
 * There is NO single ACID transaction across the Booking, Room and Payment databases, so this class never pretends
 * there is one. Instead:
 * <ul>
 *   <li>Every local state change is a SHORT transaction ({@link TransactionTemplate}); no database lock or
 *       transaction is held while a remote service is being called.</li>
 *   <li>Before the first remote side effect the booking is moved to an intermediate state
 *       (PAYMENT_IN_PROGRESS, CANCELLATION_PENDING) that is stored in the database. If the process dies or a
 *       call times out, the booking is not lost or cancelled blindly: {@link #recoverStuckBookings()} resolves it.</li>
 *   <li>Every remote operation is idempotent (payment per booking, confirm, release, refund), so a retry after a
 *       lost response is always safe.</li>
 *   <li>Every state transition re-checks the status under a row lock, so the request thread and the recovery job
 *       (or two instances) can never both complete the same step.</li>
 * </ul>
 * <pre>
 *  create : Room.createHold  ──fail──> nothing stored
 *           save PENDING     ──fail──> Room.releaseHold
 *  pay    : claim (PENDING -> PAYMENT_IN_PROGRESS, committed)
 *           Payment.process (idempotent)
 *             FAILED            ──> booking CANCELLED, Room.releaseHold
 *             rejected (4xx)    ──> back to PENDING, guest may retry
 *             timeout / outage  ──> stays PAYMENT_IN_PROGRESS, outcome looked up later (never cancelled blindly)
 *             SUCCESS           ──> paymentId stored, Room.confirmHold
 *                                     ok   ──> CONFIRMED
 *                                     fail ──> CANCELLATION_PENDING ──> refund, then release ──> CANCELLED
 *  cancel : CONFIRMED -> CANCELLATION_PENDING (committed) -> refund FIRST, release room SECOND -> CANCELLED
 *           so inventory is never freed while the guest is still paid and confirmed.
 *  expiry : unpaid PENDING bookings lapse together with their room hold.
 * </pre>
 */
@Service
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

    /** Reported to the client when the payment outcome could not be determined yet. */
    public static final String PAYMENT_STATUS_UNKNOWN = "UNKNOWN";

    private final BookingRepository bookingRepository;
    private final RoomServiceClient roomClient;
    private final PaymentServiceClient paymentClient;
    private final BookingProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingServiceImpl(BookingRepository bookingRepository, RoomServiceClient roomClient,
                              PaymentServiceClient paymentClient, BookingProperties properties, Clock clock,
                              TransactionTemplate transactionTemplate) {
        this.bookingRepository = bookingRepository;
        this.roomClient = roomClient;
        this.paymentClient = paymentClient;
        this.properties = properties;
        this.clock = clock;
        this.tx = transactionTemplate;
    }

    // ------------------------------------------------------------------ create

    @Override
    public BookingResponse createBooking(CreateBookingRequest request, AuthenticatedUser user) {
        StayDates.validate(request.checkIn(), request.checkOut(), LocalDate.now(clock), properties.maxStayNights());

        // Room Service atomically checks availability (with a row lock), prices the stay and holds the dates.
        HoldResult hold = roomClient.createHold(
                new HoldRequest(request.roomId(), request.checkIn(), request.checkOut(), request.numberOfGuests()));

        Booking booking = new Booking(user.id(), hold.roomId(), hold.hotelId(), hold.hotelName(), hold.roomNumber(),
                hold.roomType(), hold.checkIn(), hold.checkOut(), hold.guests(), hold.totalAmount(),
                hold.holdId(), hold.expiresAt());
        try {
            booking = bookingRepository.save(booking);
        } catch (RuntimeException ex) {
            log.error("Saving booking failed after hold id={} was created - releasing the hold", hold.holdId(), ex);
            releaseHoldQuietly(hold.holdId());
            throw ex;
        }
        log.info("Booking id={} created PENDING for user id={} room id={} {}..{} amount={}", booking.getId(),
                user.id(), booking.getRoomId(), booking.getCheckIn(), booking.getCheckOut(), booking.getTotalAmount());
        return BookingResponse.from(booking);
    }

    // ------------------------------------------------------------------ pay

    @Override
    public PaymentOutcomeResponse payForBooking(Long bookingId, PayBookingRequest request, AuthenticatedUser user) {
        // Step 1 (short transaction): claim the booking. A second "Pay" click or a concurrent cancel now sees
        // PAYMENT_IN_PROGRESS and is refused, so no lock needs to stay open during the remote calls below.
        PaymentClaim claim = tx.execute(status -> claimForPayment(bookingId, user));
        if (claim.expired()) {
            releaseHoldQuietly(claim.holdId());
            return new PaymentOutcomeResponse(claim.booking(), "NOT_ATTEMPTED", null,
                    "The payment window for this booking has expired. Please book again.");
        }

        // Step 2: Booking -> Payment. Idempotent per booking on the Payment Service side.
        PaymentResult payment;
        try {
            payment = paymentClient.processPayment(new PaymentCommand(bookingId, claim.amount(),
                    request.paymentMethod().name(), Boolean.TRUE.equals(request.simulateFailure())));
        } catch (DownstreamServiceException ex) {
            // The Payment Service looked at the request and refused it (4xx): nothing was charged.
            log.info("Booking id={}: payment request rejected ({}) - back to PENDING", bookingId, ex.getMessage());
            inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS, Booking::revertToPending);
            throw ex;
        } catch (RuntimeException ex) {
            // Timeout, connection loss, 5xx or no instance: the charge may or may not have happened.
            log.warn("Booking id={}: payment outcome unknown ({}) - verifying with the Payment Service",
                    bookingId, ex.getMessage());
            return resolveUnknownPayment(bookingId);
        }
        return settlePayment(bookingId, payment);
    }

    private PaymentClaim claimForPayment(Long bookingId, AuthenticatedUser user) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
        if (!booking.getUserId().equals(user.id())) {
            throw new AccessDeniedException("Only the guest who made the booking can pay for it");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessRuleException("Booking " + bookingId + " is " + booking.getStatus() + " and cannot be paid");
        }
        if (booking.isHoldExpired(clock.instant())) {
            booking.cancel("Payment window expired - the room was released");
            log.info("Booking id={} cancelled: payment window expired", bookingId);
            return new PaymentClaim(true, booking.getHoldId(), booking.getTotalAmount(), BookingResponse.from(booking));
        }
        booking.beginPayment();
        return new PaymentClaim(false, booking.getHoldId(), booking.getTotalAmount(), BookingResponse.from(booking));
    }

    /** Payment call failed without an answer: ask the Payment Service once; otherwise leave it to the recovery job. */
    private PaymentOutcomeResponse resolveUnknownPayment(Long bookingId) {
        try {
            Optional<PaymentResult> recorded = paymentClient.findByBooking(bookingId);
            if (recorded.isPresent()) {
                return settlePayment(bookingId, recorded.get());
            }
        } catch (RuntimeException ex) {
            log.warn("Booking id={}: payment lookup failed too ({})", bookingId, ex.getMessage());
        }
        return new PaymentOutcomeResponse(load(bookingId).response(), PAYMENT_STATUS_UNKNOWN, null,
                "We could not confirm your payment yet. Please do not pay again - your booking will be updated "
                        + "automatically as soon as the payment status is known.");
    }

    /**
     * Applies a KNOWN payment result to a booking in PAYMENT_IN_PROGRESS. Called by the request thread and by the
     * recovery job; whichever gets there first wins, the other finds the booking already settled.
     */
    private PaymentOutcomeResponse settlePayment(Long bookingId, PaymentResult payment) {
        if (!payment.isSuccessful()) {
            String reason = payment.failureReason() != null
                    ? "Payment failed: " + payment.failureReason() : "Payment was not successful";
            Booking settled = inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS, booking -> {
                booking.recordPayment(payment.paymentId(), payment.transactionReference());
                booking.cancel(reason);
            });
            if (settled.getStatus() == BookingStatus.CANCELLED) {
                releaseHoldQuietly(settled.getHoldId());
            }
            log.info("Booking id={} CANCELLED - payment id={} not successful, room hold released", bookingId,
                    payment.paymentId());
            return outcome(settled, payment, "Payment failed. The room has been released.");
        }

        // Persist the payment id BEFORE touching the room, so a crash from here on can always be compensated.
        Booking recorded = inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS,
                booking -> booking.recordPayment(payment.paymentId(), payment.transactionReference()));
        if (recorded.getStatus() != BookingStatus.PAYMENT_IN_PROGRESS) {
            return outcome(recorded, payment, "This booking has already been processed.");
        }

        try {
            roomClient.confirmHold(recorded.getHoldId());
        } catch (RuntimeException ex) {
            log.warn("Booking id={}: payment succeeded but room confirmation failed ({}) - refund required",
                    bookingId, ex.getMessage());
            inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS,
                    booking -> booking.requireCancellation("Room could not be confirmed"));
            completeCancellation(bookingId);
            LoadedBooking after = load(bookingId);
            String message = after.status() == BookingStatus.CANCELLED
                    ? "Room could not be confirmed - payment refunded"
                    : "Room could not be confirmed - your refund is being processed and will complete automatically";
            return new PaymentOutcomeResponse(after.response(), payment.status(), payment.transactionReference(), message);
        }

        Booking confirmed = inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS,
                booking -> booking.confirm(payment.paymentId(), payment.transactionReference()));
        log.info("Booking id={} CONFIRMED (payment {} succeeded, room hold id={} confirmed)",
                bookingId, payment.transactionReference(), confirmed.getHoldId());
        return outcome(confirmed, payment, "Payment successful. Your booking is confirmed.");
    }

    // ------------------------------------------------------------------ cancel

    @Override
    public BookingResponse cancelBooking(Long bookingId, AuthenticatedUser user) {
        CancelStep step = tx.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
            checkReadAccess(booking, user);

            switch (booking.getStatus()) {
                case CANCELLED -> throw new BusinessRuleException("Booking " + bookingId + " is already cancelled");
                case PAYMENT_IN_PROGRESS -> throw new BusinessRuleException("Payment for booking " + bookingId
                        + " is being processed - please check again in a moment");
                case CANCELLATION_PENDING -> {
                    return new CancelStep(false, true, booking.getHoldId()); // idempotent retry of an unfinished cancel
                }
                case PENDING -> {
                    booking.cancel(user.admin() ? "Cancelled by administrator" : "Cancelled by guest before payment");
                    return new CancelStep(true, false, booking.getHoldId());
                }
                case CONFIRMED -> {
                    if (!booking.getCheckIn().isAfter(LocalDate.now(clock))) {
                        throw new BusinessRuleException("Bookings can only be cancelled before the check-in date");
                    }
                    booking.requireCancellation(user.admin() ? "Cancelled by administrator" : "Cancelled by guest");
                    return new CancelStep(false, true, booking.getHoldId());
                }
                default -> throw new IllegalStateException("Unknown booking status " + booking.getStatus());
            }
        });

        if (step.releaseHold()) {
            releaseHoldQuietly(step.holdId()); // an unreleased unpaid hold still lapses on its own
        }
        if (step.completeCancellation()) {
            completeCancellation(bookingId);
        }
        log.info("Booking id={} cancel requested by user id={} (admin={})", bookingId, user.id(), user.admin());
        return load(bookingId).response();
    }

    /**
     * Finishes a cancellation: refund FIRST, release the room SECOND, then mark CANCELLED. Releasing the room
     * only after the money is returned means inventory is never freed for a new guest while the original booking
     * is still paid. Every step is idempotent; if any step fails the booking stays CANCELLATION_PENDING and the
     * recovery job retries it.
     */
    private void completeCancellation(Long bookingId) {
        Booking booking = tx.execute(status -> bookingRepository.findById(bookingId).orElse(null));
        if (booking == null || booking.getStatus() != BookingStatus.CANCELLATION_PENDING) {
            return;
        }
        if (booking.getPaymentId() != null) {
            try {
                paymentClient.refund(booking.getPaymentId());
            } catch (RuntimeException ex) {
                log.warn("Booking id={}: refund of payment id={} not completed ({}) - will be retried",
                        bookingId, booking.getPaymentId(), ex.getMessage());
                return;
            }
        }
        try {
            roomClient.releaseHold(booking.getHoldId());
        } catch (DownstreamServiceException ex) {
            if (ex.getStatus() != 404) {
                log.warn("Booking id={}: room release not completed ({}) - will be retried", bookingId, ex.getMessage());
                return;
            }
            log.info("Booking id={}: hold id={} no longer exists - nothing to release", bookingId, booking.getHoldId());
        } catch (RuntimeException ex) {
            log.warn("Booking id={}: room release not completed ({}) - will be retried", bookingId, ex.getMessage());
            return;
        }
        inTransaction(bookingId, BookingStatus.CANCELLATION_PENDING, Booking::completeCancellation);
        log.info("Booking id={} CANCELLED (refund and room release completed)", bookingId);
    }

    // ------------------------------------------------------------------ queries

    @Override
    public BookingResponse getBooking(Long bookingId, AuthenticatedUser user) {
        return tx.execute(status -> {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
            checkReadAccess(booking, user);
            return BookingResponse.from(booking);
        });
    }

    @Override
    public List<BookingResponse> myBookings(AuthenticatedUser user) {
        return tx.execute(status -> bookingRepository.findByUserIdOrderByCreatedAtDesc(user.id()).stream()
                .map(BookingResponse::from).toList());
    }

    @Override
    public List<BookingResponse> allBookings(BookingStatus bookingStatus) {
        return tx.execute(status -> {
            List<Booking> bookings = bookingStatus == null
                    ? bookingRepository.findAllByOrderByCreatedAtDesc()
                    : bookingRepository.findByStatusOrderByCreatedAtDesc(bookingStatus);
            return bookings.stream().map(BookingResponse::from).toList();
        });
    }

    @Override
    public int cancelExpiredPendingBookings() {
        return tx.execute(status -> bookingRepository.cancelExpiredPendingBookings(clock.instant(),
                BookingStatus.PENDING, BookingStatus.CANCELLED, "Payment not completed in time - the room was released"));
    }

    // ------------------------------------------------------------------ recovery

    @Override
    public int recoverStuckBookings() {
        Instant now = clock.instant();
        List<Booking> cancelling = tx.execute(status -> bookingRepository.findByStatusAndUpdatedAtBefore(
                BookingStatus.CANCELLATION_PENDING, now.minus(properties.cancellationRetryAfter())));
        List<Booking> paying = tx.execute(status -> bookingRepository.findByStatusAndUpdatedAtBefore(
                BookingStatus.PAYMENT_IN_PROGRESS, now.minus(properties.paymentRecoveryAfter())));

        for (Booking booking : cancelling) {
            try {
                completeCancellation(booking.getId());
            } catch (RuntimeException ex) {
                log.warn("Recovery of cancellation for booking id={} failed: {}", booking.getId(), ex.getMessage());
            }
        }
        for (Booking booking : paying) {
            try {
                recoverPayment(booking.getId());
            } catch (RuntimeException ex) {
                log.warn("Recovery of payment for booking id={} failed ({}) - will retry", booking.getId(), ex.getMessage());
            }
        }
        return cancelling.size() + paying.size();
    }

    private void recoverPayment(Long bookingId) {
        Optional<PaymentResult> recorded = paymentClient.findByBooking(bookingId); // throws if unreachable -> retry later
        if (recorded.isPresent()) {
            log.info("Recovery: booking id={} payment outcome is {}", bookingId, recorded.get().status());
            settlePayment(bookingId, recorded.get());
            return;
        }
        // The Payment Service never recorded an attempt, so nothing was charged. The configured recovery delay is
        // far beyond the client read timeout, so the original request is no longer in flight.
        Instant now = clock.instant();
        boolean[] lapsed = {false};
        Booking settled = inTransaction(bookingId, BookingStatus.PAYMENT_IN_PROGRESS, booking -> {
            if (booking.getHoldExpiresAt() != null && booking.getHoldExpiresAt().isAfter(now)) {
                booking.revertToPending();
            } else {
                booking.cancel("Payment was not completed in time - the room was released");
                lapsed[0] = true;
            }
        });
        if (lapsed[0] && settled.getStatus() == BookingStatus.CANCELLED) {
            releaseHoldQuietly(settled.getHoldId());
        }
        log.info("Recovery: booking id={} had no payment attempt -> {}", bookingId, settled.getStatus());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs {@code change} under a row lock but ONLY if the booking is still in {@code expected}; otherwise someone
     * else already moved it on and the current state is returned untouched.
     */
    private Booking inTransaction(Long bookingId, BookingStatus expected, Consumer<Booking> change) {
        return tx.execute(status -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
            if (booking.getStatus() == expected) {
                change.accept(booking);
            }
            return booking;
        });
    }

    private LoadedBooking load(Long bookingId) {
        return tx.execute(status -> {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
            return new LoadedBooking(booking.getStatus(), BookingResponse.from(booking));
        });
    }

    private PaymentOutcomeResponse outcome(Booking booking, PaymentResult payment, String message) {
        return new PaymentOutcomeResponse(BookingResponse.from(booking), payment.status(),
                payment.transactionReference(), message);
    }

    private static void checkReadAccess(Booking booking, AuthenticatedUser user) {
        if (!user.admin() && !booking.getUserId().equals(user.id())) {
            throw new AccessDeniedException("Booking belongs to another user");
        }
    }

    /** Compensation that must not hide the original outcome; an unreleased hold still expires on its own. */
    private void releaseHoldQuietly(Long holdId) {
        try {
            roomClient.releaseHold(holdId);
        } catch (RuntimeException ex) {
            log.warn("Could not release hold id={} ({}); it will expire automatically", holdId, ex.getMessage());
        }
    }

    private record PaymentClaim(boolean expired, Long holdId, BigDecimal amount, BookingResponse booking) {
    }

    private record CancelStep(boolean releaseHold, boolean completeCancellation, Long holdId) {
    }

    private record LoadedBooking(BookingStatus status, BookingResponse response) {
    }
}
