package com.grandvista.paymentservice.service.impl;

import com.grandvista.paymentservice.config.PaymentProperties;
import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.entity.Payment;
import com.grandvista.paymentservice.entity.PaymentMethod;
import com.grandvista.paymentservice.entity.PaymentStatus;
import com.grandvista.paymentservice.exception.BusinessRuleException;
import com.grandvista.paymentservice.repository.PaymentRepository;
import com.grandvista.paymentservice.service.SimulatedPaymentGateway;
import com.grandvista.paymentservice.util.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser(7L, false);
    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(new PaymentProperties(new BigDecimal("500000")));
        service = new PaymentServiceImpl(paymentRepository, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void approvedPaymentIsStoredAsSuccessWithTransactionReference() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = service.processPayment(request("16500.00", false), USER);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.transactionReference()).matches("GV-PAY-20260914-[0-9A-F]{8}");
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void simulatedFailureIsStoredAsFailedWithReason() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = service.processPayment(request("16500.00", true), USER);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).contains("declined");
    }

    @Test
    void amountAboveGatewayLimitFails() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = service.processPayment(request("600000.00", false), USER);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).contains("limit");
    }

    @Test
    void bookingCannotBePaidTwice() {
        when(paymentRepository.existsByBookingIdAndStatus(42L, PaymentStatus.SUCCESS)).thenReturn(true);

        assertThatThrownBy(() -> service.processPayment(request("16500.00", false), USER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been paid");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundMovesSuccessToRefundedAndIsIdempotent() {
        Payment payment = payment(PaymentStatus.SUCCESS);
        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));

        assertThat(service.refund(1L, USER).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(service.refund(1L, USER).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(NOW);
    }

    @Test
    void failedPaymentCannotBeRefunded() {
        when(paymentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(payment(PaymentStatus.FAILED)));

        assertThatThrownBy(() -> service.refund(1L, USER)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void usersCannotReadOtherUsersPayments() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.SUCCESS)));

        assertThatThrownBy(() -> service.getPayment(1L, new AuthenticatedUser(8L, false)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.getPayment(1L, new AuthenticatedUser(1L, true)).paymentId()).isNull();
    }

    private static PaymentRequest request(String amount, boolean simulateFailure) {
        return new PaymentRequest(42L, new BigDecimal(amount), PaymentMethod.UPI, simulateFailure);
    }

    private static Payment payment(PaymentStatus status) {
        return new Payment(42L, 7L, new BigDecimal("16500.00"), "INR", PaymentMethod.CARD, status,
                "GV-PAY-20260914-ABCDEF12", null);
    }
}
