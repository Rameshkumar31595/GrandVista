package com.grandvista.paymentservice.entity;

/**
 * Payments are processed synchronously by the simulated gateway, so a stored payment is
 * already either SUCCESS or FAILED. REFUNDED is used when a paid booking is cancelled or
 * cannot be confirmed (compensation).
 */
public enum PaymentStatus {
    SUCCESS,
    FAILED,
    REFUNDED
}
