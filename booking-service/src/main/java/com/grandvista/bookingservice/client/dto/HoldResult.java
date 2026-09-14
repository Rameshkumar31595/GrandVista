package com.grandvista.bookingservice.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Response of the Room Service hold endpoints. Enum values are kept as strings to avoid coupling. */
public record HoldResult(
        Long holdId,
        Long roomId,
        Long hotelId,
        String hotelName,
        String roomNumber,
        String roomType,
        Long userId,
        LocalDate checkIn,
        LocalDate checkOut,
        Integer guests,
        BigDecimal totalAmount,
        String status,
        Instant expiresAt
) {
}
