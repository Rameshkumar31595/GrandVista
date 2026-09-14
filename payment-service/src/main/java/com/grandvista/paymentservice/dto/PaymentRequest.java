package com.grandvista.paymentservice.dto;

import com.grandvista.paymentservice.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Internal request from the Booking Service. The amount comes from the booking (which got it
 * from the Room Service price calculation) - never from the browser.
 *
 * @param simulateFailure demo switch: true makes the simulated gateway decline the payment
 */
public record PaymentRequest(

        @NotNull(message = "bookingId is required")
        @Positive(message = "bookingId must be positive")
        Long bookingId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "1.00", message = "amount must be positive")
        @Digits(integer = 10, fraction = 2, message = "amount may have at most 2 decimal places")
        BigDecimal amount,

        @NotNull(message = "Payment method is required (CARD, UPI, NET_BANKING or WALLET)")
        PaymentMethod method,

        Boolean simulateFailure
) {
}
