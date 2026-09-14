package com.grandvista.bookingservice.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Booking request from the browser. It intentionally has NO price field: the amount is always
 * calculated by the Room Service so a client cannot choose its own price.
 */
public record CreateBookingRequest(

        @NotNull(message = "roomId is required")
        @Positive(message = "roomId must be positive")
        Long roomId,

        @NotNull(message = "Check-in date is required")
        @FutureOrPresent(message = "Check-in date cannot be in the past")
        LocalDate checkIn,

        @NotNull(message = "Check-out date is required")
        LocalDate checkOut,

        @NotNull(message = "Number of guests is required")
        @Min(value = 1, message = "At least one guest is required")
        @Max(value = 10, message = "At most 10 guests are allowed")
        Integer numberOfGuests
) {
}
