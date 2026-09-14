package com.grandvista.bookingservice.util;

import com.grandvista.bookingservice.exception.InvalidRequestException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Early validation of the requested stay so obviously invalid requests are rejected without
 * calling other services. The Room Service repeats these checks because it owns the inventory.
 */
public final class StayDates {

    private StayDates() {
    }

    public static long validate(LocalDate checkIn, LocalDate checkOut, LocalDate today, int maxNights) {
        if (checkIn == null || checkOut == null) {
            throw new InvalidRequestException("Both checkIn and checkOut dates are required");
        }
        if (checkIn.isBefore(today)) {
            throw new InvalidRequestException("Check-in date cannot be in the past");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidRequestException("Check-out date must be after check-in date");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > maxNights) {
            throw new InvalidRequestException("A single booking cannot exceed " + maxNights + " nights");
        }
        return nights;
    }
}
