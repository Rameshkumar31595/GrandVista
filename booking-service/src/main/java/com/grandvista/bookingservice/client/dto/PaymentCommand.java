package com.grandvista.bookingservice.client.dto;

import java.math.BigDecimal;

/** Body of Payment Service POST /internal/payments. */
public record PaymentCommand(Long bookingId, BigDecimal amount, String method, Boolean simulateFailure) {
}
