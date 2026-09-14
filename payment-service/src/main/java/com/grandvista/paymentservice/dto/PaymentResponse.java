package com.grandvista.paymentservice.dto;

import com.grandvista.paymentservice.entity.Payment;
import com.grandvista.paymentservice.entity.PaymentMethod;
import com.grandvista.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long paymentId,
        Long bookingId,
        Long userId,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        PaymentStatus status,
        String transactionReference,
        String failureReason,
        Instant createdAt,
        Instant refundedAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getBookingId(), payment.getUserId(), payment.getAmount(),
                payment.getCurrency(), payment.getMethod(), payment.getStatus(), payment.getTransactionReference(),
                payment.getFailureReason(), payment.getCreatedAt(), payment.getRefundedAt());
    }
}
