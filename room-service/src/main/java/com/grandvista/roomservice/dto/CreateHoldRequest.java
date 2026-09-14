package com.grandvista.roomservice.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Internal request sent by the Booking Service to reserve a room for a date range.
 */
public record CreateHoldRequest(

        @NotNull(message = "roomId is required")
        Long roomId,

        @NotNull(message = "Check-in date is required")
        @FutureOrPresent(message = "Check-in date cannot be in the past")
        LocalDate checkIn,

        @NotNull(message = "Check-out date is required")
        LocalDate checkOut,

        @NotNull(message = "Number of guests is required")
        @Min(value = 1, message = "At least one guest is required")
        @Max(value = 10, message = "At most 10 guests are allowed")
        Integer guests
) {
}
