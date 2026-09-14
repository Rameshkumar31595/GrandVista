package com.grandvista.bookingservice.dto;

/**
 * Payment method type only - no card numbers, CVV or bank credentials are collected.
 */
public enum PaymentMethod {
    CARD,
    UPI,
    NET_BANKING,
    WALLET
}
