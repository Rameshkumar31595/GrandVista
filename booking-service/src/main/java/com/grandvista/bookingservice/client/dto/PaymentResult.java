package com.grandvista.bookingservice.client.dto;

import java.math.BigDecimal;

/** Response of the Payment Service internal endpoints. */
public record PaymentResult(
        Long paymentId,
        Long bookingId,
        BigDecimal amount,
        String status,
        String transactionReference,
        String failureReason
) {

    public boolean isSuccessful() {
        return "SUCCESS".equals(status);
    }
}
