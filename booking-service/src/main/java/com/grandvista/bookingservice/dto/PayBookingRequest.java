package com.grandvista.bookingservice.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param simulateFailure demonstration switch that makes the mock payment gateway decline
 */
public record PayBookingRequest(

        @NotNull(message = "Payment method is required (CARD, UPI, NET_BANKING or WALLET)")
        PaymentMethod paymentMethod,

        Boolean simulateFailure
) {
}
