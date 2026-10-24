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
import com.grandvista.bookingservice.dto.PaymentMethod;
import com.grandvista.bookingservice.dto.PaymentOutcomeResponse;
import com.grandvista.bookingservice.entity.Booking;
import com.grandvista.bookingservice.entity.BookingStatus;
import com.grandvista.bookingservice.exception.BusinessRuleException;
import com.grandvista.bookingservice.exception.DownstreamServiceException;
import com.grandvista.bookingservice.exception.InvalidRequestException;
import com.grandvista.bookingservice.exception.ServiceUnavailableException;
import com.grandvista.bookingservice.repository.BookingRepository;
import com.grandvista.bookingservice.util.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The repository is backed by an in-memory map so that the saga's state transitions (including the persisted
 * recovery states) are really exercised, not just stubbed.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 23);
    private static final AuthenticatedUser GUEST = new AuthenticatedUser(5L, false);
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, true);
    private static final PayBookingRequest PAY_UPI = new PayBookingRequest(PaymentMethod.UPI, false);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomServiceClient roomClient;
    @Mock
    private PaymentServiceClient paymentClient;

    private final Map<Long, Booking> store = new HashMap<>();
    private BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(bookingRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<Long>getArgument(0))));
        lenient().when(bookingRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.<Long>getArgument(0))));
        lenient().when(bookingRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenAnswer(invocation -> {
                    List<Booking> matches = new ArrayList<>();
                    store.values().stream().filter(b -> b.getStatus() == invocation.<BookingStatus>getArgument(0))
                            .forEach(matches::add);
                    return matches;
                });
        service = new BookingServiceImpl(bookingRepository, roomClient, paymentClient,
                new BookingProperties(30, Duration.ofSeconds(60), Duration.ofSeconds(30)),
                Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    // ------------------------------------------------------------------ create

    @Test
    void reversedDateRangeIsRejectedBeforeCallingRoomService() {
        CreateBookingRequest request = new CreateBookingRequest(10L, CHECK_OUT, CHECK_IN, 2);

        assertThatThrownBy(() -> service.createBooking(request, GUEST))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("after check-in");
        verifyNoInteractions(roomClient, bookingRepository);
    }

    @Test
    void overlappingReservationRejectedByRoomServiceCreatesNoBooking() {
        when(roomClient.createHold(any())).thenThrow(new DownstreamServiceException("ROOM-SERVICE", 409,
                "Room 203 is already reserved for some of the selected dates"));

        assertThatThrownBy(() -> service.createBooking(new CreateBookingRequest(10L, CHECK_IN, CHECK_OUT, 2), GUEST))
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageContaining("already reserved");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void successfulCreationStoresPendingBookingWithRoomServicePrice() {
        when(roomClient.createHold(new HoldRequest(10L, CHECK_IN, CHECK_OUT, 2))).thenReturn(hold());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 77L));

        BookingResponse response = service.createBooking(new CreateBookingRequest(10L, CHECK_IN, CHECK_OUT, 2), GUEST);

        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("16500.00");
        assertThat(response.userId()).isEqualTo(5L);
        assertThat(response.nights()).isEqualTo(3);
        assertThat(response.paymentDueBy()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void holdIsReleasedWhenBookingCannotBeSaved() {
        when(roomClient.createHold(any())).thenReturn(hold());
        when(bookingRepository.save(any(Booking.class))).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.createBooking(new CreateBookingRequest(10L, CHECK_IN, CHECK_OUT, 2), GUEST))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(roomClient).releaseHold(300L);
    }

    // ------------------------------------------------------------------ pay

    @Test
    void successfulPaymentConfirmsRoomAndBooking() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenReturn(payment("SUCCESS", null));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        ArgumentCaptor<PaymentCommand> command = ArgumentCaptor.forClass(PaymentCommand.class);
        InOrder order = inOrder(paymentClient, roomClient);
        order.verify(paymentClient).processPayment(command.capture());
        order.verify(roomClient).confirmHold(300L);
        assertThat(command.getValue().amount()).isEqualByComparingTo("16500.00");
        assertThat(command.getValue().bookingId()).isEqualTo(77L);
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outcome.booking().paymentReference()).isEqualTo("GV-PAY-20260914-ABCDEF12");
        assertThat(booking.getPaymentId()).isEqualTo(900L);
        verify(roomClient, never()).releaseHold(any());
    }

    @Test
    void failedPaymentCancelsBookingAndReleasesRoom() {
        store(pendingBooking());
        when(paymentClient.processPayment(any())).thenReturn(payment("FAILED", "Declined (demo failure)"));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, new PayBookingRequest(PaymentMethod.CARD, true), GUEST);

        assertThat(outcome.paymentStatus()).isEqualTo("FAILED");
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outcome.booking().statusReason()).contains("Payment failed");
        verify(roomClient).releaseHold(300L);
        verify(roomClient, never()).confirmHold(any());
        verify(paymentClient, never()).refund(any());
    }

    @Test
    void roomConfirmationFailureRefundsFirstAndThenReleasesTheRoom() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenReturn(payment("SUCCESS", null));
        when(roomClient.confirmHold(300L)).thenThrow(new ServiceUnavailableException("ROOM-SERVICE", null));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        InOrder order = inOrder(paymentClient, roomClient);
        order.verify(paymentClient).refund(900L);
        order.verify(roomClient).releaseHold(300L);
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getStatusReason()).isEqualTo("Room could not be confirmed - payment refunded");
        assertThat(outcome.message()).isEqualTo("Room could not be confirmed - payment refunded");
    }

    @Test
    void failedRefundKeepsBookingInCancellationPendingAndNeverFreesTheRoom() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenReturn(payment("SUCCESS", null));
        when(roomClient.confirmHold(300L)).thenThrow(new ServiceUnavailableException("ROOM-SERVICE", null));
        when(paymentClient.refund(900L)).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        assertThat(outcome.message()).contains("refund is being processed");
        verify(roomClient, never()).releaseHold(any()); // inventory stays protected until the money is returned
    }

    @Test
    void recoveryCompletesAnInterruptedCancellationOnceRefundWorksAgain() {
        Booking booking = store(pendingBooking());
        booking.recordPayment(900L, "GV-PAY-1");
        booking.requireCancellation("Cancelled by guest");

        int examined = service.recoverStuckBookings();

        InOrder order = inOrder(paymentClient, roomClient);
        order.verify(paymentClient).refund(900L);
        order.verify(roomClient).releaseHold(300L);
        assertThat(examined).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getStatusReason()).isEqualTo("Cancelled by guest - payment refunded");
    }

    @Test
    void recoveryTreatsAnAlreadyMissingHoldAsReleased() {
        Booking booking = store(pendingBooking());
        booking.recordPayment(900L, "GV-PAY-1");
        booking.requireCancellation("Cancelled by guest");
        when(roomClient.releaseHold(300L)).thenThrow(new DownstreamServiceException("ROOM-SERVICE", 404, "gone"));

        service.recoverStuckBookings();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void paymentTimeoutIsNeverCancelledBlindlyAndStaysInProgress() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.empty());

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        assertThat(outcome.paymentStatus()).isEqualTo(BookingServiceImpl.PAYMENT_STATUS_UNKNOWN);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_IN_PROGRESS);
        verify(roomClient, never()).releaseHold(any());
        verify(paymentClient, never()).refund(any());
    }

    @Test
    void lostPaymentResponseIsResolvedByLookingUpTheRecordedSuccess() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.of(payment("SUCCESS", null)));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        verify(roomClient).confirmHold(300L);
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentId()).isEqualTo(900L);
    }

    @Test
    void lostPaymentResponseIsResolvedByLookingUpARecordedFailure() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.of(payment("FAILED", "Declined")));

        service.payForBooking(77L, PAY_UPI, GUEST);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(roomClient).releaseHold(300L);
    }

    @Test
    void recoveryResolvesStuckPaymentThatActuallySucceeded() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.of(payment("SUCCESS", null)));

        service.recoverStuckBookings();

        verify(roomClient).confirmHold(300L);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void recoveryRefundsWhenPaidBookingsRoomHoldHasMeanwhileExpired() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.of(payment("SUCCESS", null)));
        when(roomClient.confirmHold(300L)).thenThrow(new DownstreamServiceException("ROOM-SERVICE", 409, "expired"));

        service.recoverStuckBookings();

        verify(paymentClient).refund(900L);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void recoveryReturnsToPendingWhenNoPaymentWasEverRecordedAndHoldIsStillValid() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.empty());

        service.recoverStuckBookings();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(roomClient, never()).releaseHold(any());
    }

    @Test
    void recoveryCancelsWhenNoPaymentWasRecordedAndHoldHasExpired() {
        Booking booking = store(withId(new Booking(5L, 10L, 1L, "GrandVista", "203", "DELUXE", CHECK_IN, CHECK_OUT, 2,
                new BigDecimal("16500.00"), 300L, NOW.minusSeconds(1)), 77L));
        booking.beginPayment();
        when(paymentClient.findByBooking(77L)).thenReturn(Optional.empty());

        service.recoverStuckBookings();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(roomClient).releaseHold(300L);
    }

    @Test
    void recoveryLeavesBookingUntouchedWhenPaymentServiceIsStillDown() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();
        when(paymentClient.findByBooking(77L)).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));

        service.recoverStuckBookings();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAYMENT_IN_PROGRESS);
        verify(roomClient, never()).releaseHold(any());
        verify(paymentClient, never()).refund(any());
    }

    @Test
    void rejectedPaymentRequestReturnsBookingToPendingSoTheGuestCanRetry() {
        Booking booking = store(pendingBooking());
        when(paymentClient.processPayment(any())).thenThrow(new DownstreamServiceException("PAYMENT-SERVICE", 400, "bad"));

        assertThatThrownBy(() -> service.payForBooking(77L, PAY_UPI, GUEST))
                .isInstanceOf(DownstreamServiceException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(roomClient, never()).releaseHold(any());
    }

    @Test
    void secondPayClickWhilePaymentIsInProgressIsRefusedWithoutAnotherCharge() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();

        assertThatThrownBy(() -> service.payForBooking(77L, PAY_UPI, GUEST))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PAYMENT_IN_PROGRESS");
        verifyNoInteractions(paymentClient);
    }

    @Test
    void expiredPaymentWindowCancelsWithoutCharging() {
        store(withId(new Booking(5L, 10L, 1L, "GrandVista", "203", "DELUXE", CHECK_IN, CHECK_OUT, 2,
                new BigDecimal("16500.00"), 300L, NOW.minusSeconds(1)), 77L));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, PAY_UPI, GUEST);

        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outcome.paymentStatus()).isEqualTo("NOT_ATTEMPTED");
        verifyNoInteractions(paymentClient);
    }

    @Test
    void confirmedBookingCannotBePaidAgain() {
        Booking booking = store(pendingBooking());
        booking.confirm(900L, "GV-PAY-1");

        assertThatThrownBy(() -> service.payForBooking(77L, PAY_UPI, GUEST)).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void anotherUserCannotPayOrViewTheBooking() {
        store(pendingBooking());
        AuthenticatedUser stranger = new AuthenticatedUser(6L, false);

        assertThatThrownBy(() -> service.payForBooking(77L, PAY_UPI, stranger)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getBooking(77L, stranger)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.getBooking(77L, ADMIN).bookingId()).isEqualTo(77L);
        verifyNoInteractions(paymentClient);
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancellingConfirmedBookingRefundsBeforeReleasingTheRoom() {
        Booking booking = store(pendingBooking());
        booking.confirm(900L, "GV-PAY-1");

        BookingResponse response = service.cancelBooking(77L, GUEST);

        InOrder order = inOrder(paymentClient, roomClient);
        order.verify(paymentClient).refund(900L);
        order.verify(roomClient).releaseHold(300L);
        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.statusReason()).isEqualTo("Cancelled by guest - payment refunded");
    }

    @Test
    void refundFailureDuringCancellationKeepsRoomReservedAndBookingRecoverable() {
        Booking booking = store(pendingBooking());
        booking.confirm(900L, "GV-PAY-1");
        when(paymentClient.refund(900L)).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));

        BookingResponse response = service.cancelBooking(77L, GUEST);

        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
        verify(roomClient, never()).releaseHold(any());

        // later the Payment Service is back: the recovery job finishes the cancellation
        doReturn(payment("REFUNDED", null)).when(paymentClient).refund(900L);
        service.recoverStuckBookings();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(roomClient).releaseHold(300L);
    }

    @Test
    void cancellingAPendingBookingReleasesTheHoldWithoutRefund() {
        Booking booking = store(pendingBooking());

        BookingResponse response = service.cancelBooking(77L, GUEST);

        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(roomClient).releaseHold(300L);
        verify(paymentClient, never()).refund(any());
        assertThat(booking.getStatusReason()).isEqualTo("Cancelled by guest before payment");
    }

    @Test
    void cancelWhilePaymentIsInProgressIsRefused() {
        Booking booking = store(pendingBooking());
        booking.beginPayment();

        assertThatThrownBy(() -> service.cancelBooking(77L, GUEST))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("being processed");
        verifyNoInteractions(paymentClient, roomClient);
    }

    @Test
    void repeatedCancelOfAnUnfinishedCancellationJustRetriesIt() {
        Booking booking = store(pendingBooking());
        booking.recordPayment(900L, "GV-PAY-1");
        booking.requireCancellation("Cancelled by guest");

        BookingResponse response = service.cancelBooking(77L, GUEST);

        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(paymentClient).refund(900L);
    }

    @Test
    void cancellingAnAlreadyCancelledBookingIsAConflict() {
        Booking booking = store(pendingBooking());
        booking.cancel("done");

        assertThatThrownBy(() -> service.cancelBooking(77L, GUEST)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void confirmedBookingCannotBeCancelledOnOrAfterCheckIn() {
        Booking booking = new Booking(5L, 10L, 1L, "GrandVista", "203", "DELUXE", LocalDate.of(2026, 9, 14), CHECK_OUT, 2,
                new BigDecimal("16500.00"), 300L, NOW.plusSeconds(600));
        store(withId(booking, 77L));
        booking.confirm(900L, "GV-PAY-1");

        assertThatThrownBy(() -> service.cancelBooking(77L, GUEST))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("before the check-in");
        verifyNoInteractions(paymentClient);
    }

    @Test
    void adminCancellationIsRecordedAsSuch() {
        Booking booking = store(pendingBooking());
        booking.confirm(900L, "GV-PAY-1");

        service.cancelBooking(77L, ADMIN);

        assertThat(booking.getStatusReason()).isEqualTo("Cancelled by administrator - payment refunded");
    }

    // ------------------------------------------------------------------ helpers

    private Booking store(Booking booking) {
        store.put(booking.getId(), booking);
        return booking;
    }

    private static HoldResult hold() {
        return new HoldResult(300L, 10L, 1L, "GrandVista Banjara Residency", "203", "DELUXE", 5L, CHECK_IN, CHECK_OUT,
                2, new BigDecimal("16500.00"), "HELD", NOW.plusSeconds(900));
    }

    private static PaymentResult payment(String status, String reason) {
        return new PaymentResult(900L, 77L, new BigDecimal("16500.00"), status, "GV-PAY-20260914-ABCDEF12", reason);
    }

    private static Booking pendingBooking() {
        Booking booking = new Booking(5L, 10L, 1L, "GrandVista Banjara Residency", "203", "DELUXE", CHECK_IN, CHECK_OUT,
                2, new BigDecimal("16500.00"), 300L, NOW.plusSeconds(600));
        return withId(booking, 77L);
    }

    private static Booking withId(Booking booking, Long id) {
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }
}
