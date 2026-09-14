package com.grandvista.bookingservice.dto;

/**
 * Result of the Booking -> Payment -> Room workflow, shown on the payment result page.
 */
public record PaymentOutcomeResponse(
        BookingResponse booking,
        String paymentStatus,
        String transactionReference,
        String message
) {
}
