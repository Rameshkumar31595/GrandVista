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
import com.grandvista.bookingservice.exception.ResourceNotFoundException;
import com.grandvista.bookingservice.repository.BookingRepository;
import com.grandvista.bookingservice.service.BookingService;
import com.grandvista.bookingservice.util.AuthenticatedUser;
import com.grandvista.bookingservice.util.StayDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Orchestrates the distributed booking workflow (a simple orchestration-based saga).
 * <p>
 * There is NO single ACID transaction across the Booking, Room and Payment databases. Instead
 * each step commits locally and every failure path has an explicit compensating action:
 * <pre>
 *  create : Room.createHold  ──fail──> nothing stored
 *           save PENDING     ──fail──> Room.releaseHold
 *  pay    : Payment.process  ──FAILED──> Room.releaseHold, booking CANCELLED
 *           Room.confirmHold ──fail────> Payment.refund,   booking CANCELLED
 *  expiry : unpaid holds lapse automatically in Room Service; booking marked CANCELLED by a job
 * </pre>
 */
@Service
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final RoomServiceClient roomClient;
    private final PaymentServiceClient paymentClient;
    private final BookingProperties properties;
    private final Clock clock;

    public BookingServiceImpl(BookingRepository bookingRepository, RoomServiceClient roomClient,
                              PaymentServiceClient paymentClient, BookingProperties properties, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.roomClient = roomClient;
        this.paymentClient = paymentClient;
        this.properties = properties;
        this.clock = clock;
    }

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

    /**
     * The booking row stays locked (SELECT ... FOR UPDATE) for the duration of this method, so two
     * simultaneous "Pay" clicks for the same booking are processed one after the other and the second
     * one sees that the booking is no longer PENDING.
     */
    @Override
    @Transactional
    public PaymentOutcomeResponse payForBooking(Long bookingId, PayBookingRequest request, AuthenticatedUser user) {
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
            releaseHoldQuietly(booking.getHoldId());
            log.info("Booking id={} cancelled: payment window expired", bookingId);
            return new PaymentOutcomeResponse(BookingResponse.from(booking), "NOT_ATTEMPTED", null,
                    "The payment window for this booking has expired. Please book again.");
        }

        // ---- Booking -> Payment ----
        PaymentResult payment = paymentClient.processPayment(new PaymentCommand(booking.getId(),
                booking.getTotalAmount(), request.paymentMethod().name(), Boolean.TRUE.equals(request.simulateFailure())));
        booking.recordPayment(payment.paymentId(), payment.transactionReference());

        if (!payment.isSuccessful()) {
            booking.cancel("Payment failed: " + payment.failureReason());
            releaseHoldQuietly(booking.getHoldId());
            log.info("Booking id={} CANCELLED - payment id={} failed, room hold released", bookingId, payment.paymentId());
            return new PaymentOutcomeResponse(BookingResponse.from(booking), payment.status(),
                    payment.transactionReference(), "Payment failed. The room has been released.");
        }

        // ---- Payment -> Room update ----
        try {
            roomClient.confirmHold(booking.getHoldId());
        } catch (RuntimeException ex) {
            log.warn("Booking id={}: payment succeeded but room confirmation failed ({}) - refunding",
                    bookingId, ex.getMessage());
            String reason = refundQuietly(payment.paymentId())
                    ? "Room could not be confirmed - payment refunded"
                    : "Room could not be confirmed - refund pending manual review";
            booking.cancel(reason);
            releaseHoldQuietly(booking.getHoldId());
            return new PaymentOutcomeResponse(BookingResponse.from(booking), payment.status(),
                    payment.transactionReference(), reason);
        }

        booking.confirm(payment.paymentId(), payment.transactionReference());
        log.info("Booking id={} CONFIRMED (payment {} succeeded, room hold id={} confirmed)",
                bookingId, payment.transactionReference(), booking.getHoldId());
        return new PaymentOutcomeResponse(BookingResponse.from(booking), payment.status(),
                payment.transactionReference(), "Payment successful. Your booking is confirmed.");
    }

    @Override
    @Transactional
    public BookingResponse cancelBooking(Long bookingId, AuthenticatedUser user) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
        checkReadAccess(booking, user);

        switch (booking.getStatus()) {
            case CANCELLED -> throw new BusinessRuleException("Booking " + bookingId + " is already cancelled");
            case PENDING -> {
                roomClient.releaseHold(booking.getHoldId());
                booking.cancel(user.admin() ? "Cancelled by administrator" : "Cancelled by guest before payment");
            }
            case CONFIRMED -> {
                if (!booking.getCheckIn().isAfter(LocalDate.now(clock))) {
                    throw new BusinessRuleException("Bookings can only be cancelled before the check-in date");
                }
                // Release the room first, then refund. Both calls are idempotent, so a retry after a
                // partial failure is safe.
                roomClient.releaseHold(booking.getHoldId());
                if (booking.getPaymentId() != null) {
                    paymentClient.refund(booking.getPaymentId());
                }
                booking.cancel(user.admin() ? "Cancelled by administrator - payment refunded"
                        : "Cancelled by guest - payment refunded");
            }
            default -> throw new IllegalStateException("Unknown booking status " + booking.getStatus());
        }
        log.info("Booking id={} cancelled by user id={} (admin={})", bookingId, user.id(), user.admin());
        return BookingResponse.from(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId, AuthenticatedUser user) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking " + bookingId + " not found"));
        checkReadAccess(booking, user);
        return BookingResponse.from(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> myBookings(AuthenticatedUser user) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(user.id()).stream().map(BookingResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponse> allBookings(BookingStatus status) {
        List<Booking> bookings = status == null
                ? bookingRepository.findAllByOrderByCreatedAtDesc()
                : bookingRepository.findByStatusOrderByCreatedAtDesc(status);
        return bookings.stream().map(BookingResponse::from).toList();
    }

    @Override
    @Transactional
    public int cancelExpiredPendingBookings() {
        return bookingRepository.cancelExpiredPendingBookings(clock.instant(), BookingStatus.PENDING,
                BookingStatus.CANCELLED, "Payment not completed in time - the room was released");
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

    private boolean refundQuietly(Long paymentId) {
        try {
            paymentClient.refund(paymentId);
            return true;
        } catch (RuntimeException ex) {
            log.error("Refund of payment id={} failed - manual review required: {}", paymentId, ex.getMessage());
            return false;
        }
    }
}
