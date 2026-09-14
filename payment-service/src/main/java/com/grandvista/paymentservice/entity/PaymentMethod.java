package com.grandvista.paymentservice.entity;

/**
 * Only the method TYPE is recorded. No card numbers, CVV, UPI PINs or bank credentials
 * are ever accepted or stored.
 */
public enum PaymentMethod {
    CARD,
    UPI,
    NET_BANKING,
    WALLET
}
