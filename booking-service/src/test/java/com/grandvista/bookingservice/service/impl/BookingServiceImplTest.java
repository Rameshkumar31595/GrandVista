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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 23);
    private static final AuthenticatedUser GUEST = new AuthenticatedUser(5L, false);
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, true);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomServiceClient roomClient;
    @Mock
    private PaymentServiceClient paymentClient;

    private BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl(bookingRepository, roomClient, paymentClient, new BookingProperties(30),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
        Booking booking = pendingBooking();
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));
        when(paymentClient.processPayment(any())).thenReturn(payment("SUCCESS", null));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), GUEST);

        ArgumentCaptor<PaymentCommand> command = ArgumentCaptor.forClass(PaymentCommand.class);
        InOrder order = inOrder(paymentClient, roomClient);
        order.verify(paymentClient).processPayment(command.capture());
        order.verify(roomClient).confirmHold(300L);
        assertThat(command.getValue().amount()).isEqualByComparingTo("16500.00");
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outcome.booking().paymentReference()).isEqualTo("GV-PAY-20260914-ABCDEF12");
        verify(roomClient, never()).releaseHold(any());
    }

    @Test
    void failedPaymentCancelsBookingAndReleasesRoom() {
        Booking booking = pendingBooking();
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));
        when(paymentClient.processPayment(any())).thenReturn(payment("FAILED", "Declined (demo failure)"));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, new PayBookingRequest(PaymentMethod.CARD, true), GUEST);

        assertThat(outcome.paymentStatus()).isEqualTo("FAILED");
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outcome.booking().statusReason()).contains("Payment failed");
        verify(roomClient).releaseHold(300L);
        verify(roomClient, never()).confirmHold(any());
    }

    @Test
    void roomConfirmationFailureTriggersRefundCompensation() {
        Booking booking = pendingBooking();
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));
        when(paymentClient.processPayment(any())).thenReturn(payment("SUCCESS", null));
        when(roomClient.confirmHold(300L)).thenThrow(new ServiceUnavailableException("ROOM-SERVICE", null));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), GUEST);

        verify(paymentClient).refund(900L);
        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outcome.booking().statusReason()).isEqualTo("Room could not be confirmed - payment refunded");
    }

    @Test
    void paymentServiceOutageLeavesBookingPendingForRetry() {
        Booking booking = pendingBooking();
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));
        when(paymentClient.processPayment(any())).thenThrow(new ServiceUnavailableException("PAYMENT-SERVICE", null));

        assertThatThrownBy(() -> service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), GUEST))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(roomClient, never()).releaseHold(any());
    }

    @Test
    void expiredPaymentWindowCancelsWithoutCharging() {
        Booking booking = new Booking(5L, 10L, 1L, "GrandVista", "203", "DELUXE", CHECK_IN, CHECK_OUT, 2,
                new BigDecimal("16500.00"), 300L, NOW.minusSeconds(1));
        withId(booking, 77L);
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));

        PaymentOutcomeResponse outcome = service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), GUEST);

        assertThat(outcome.booking().bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void confirmedBookingCannotBePaidAgain() {
        Booking booking = pendingBooking();
        booking.confirm(900L, "GV-PAY-1");
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), GUEST))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void anotherUserCannotPayOrViewTheBooking() {
        Booking booking = pendingBooking();
        AuthenticatedUser stranger = new AuthenticatedUser(6L, false);
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));
        when(bookingRepository.findById(77L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.payForBooking(77L, new PayBookingRequest(PaymentMethod.UPI, false), stranger))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getBooking(77L, stranger)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.getBooking(77L, ADMIN).bookingId()).isEqualTo(77L);
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancellingConfirmedBookingReleasesRoomAndRefunds() {
        Booking booking = pendingBooking();
        booking.confirm(900L, "GV-PAY-1");
        when(bookingRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(booking));

        BookingResponse response = service.cancelBooking(77L, GUEST);

        verify(roomClient).releaseHold(300L);
        verify(paymentClient).refund(900L);
        assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
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
