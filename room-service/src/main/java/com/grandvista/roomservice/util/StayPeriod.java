package com.grandvista.roomservice.util;

import com.grandvista.roomservice.exception.InvalidRequestException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A hotel stay: nights from {@code checkIn} (inclusive) to {@code checkOut} (exclusive).
 */
public record StayPeriod(LocalDate checkIn, LocalDate checkOut) {

    /**
     * Validates the date range against business rules.
     *
     * @throws InvalidRequestException for missing, past or reversed dates, or an over-long stay
     */
    public static StayPeriod of(LocalDate checkIn, LocalDate checkOut, LocalDate today, int maxNights) {
        if (checkIn == null || checkOut == null) {
            throw new InvalidRequestException("Both checkIn and checkOut dates are required");
        }
        if (checkIn.isBefore(today)) {
            throw new InvalidRequestException("Check-in date cannot be in the past");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidRequestException("Check-out date must be after check-in date");
        }
        StayPeriod period = new StayPeriod(checkIn, checkOut);
        if (period.nights() > maxNights) {
            throw new InvalidRequestException("A single booking cannot exceed " + maxNights + " nights");
        }
        return period;
    }

    public long nights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** Each night of the stay, identified by its date. */
    public List<LocalDate> nightDates() {
        return checkIn.datesUntil(checkOut).toList();
    }

    /**
     * Two stays overlap when each one starts before the other ends. Because check-out is
     * exclusive, a stay ending on the 23rd does not overlap a stay starting on the 23rd.
     * The same rule is used by the database query in RoomHoldRepository.
     */
    public boolean overlaps(StayPeriod other) {
        return checkIn.isBefore(other.checkOut) && checkOut.isAfter(other.checkIn);
    }
}
