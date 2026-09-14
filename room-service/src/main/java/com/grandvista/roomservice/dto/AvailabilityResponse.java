package com.grandvista.roomservice.dto;

import java.time.LocalDate;

public record AvailabilityResponse(
        Long roomId,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        boolean available,
        String reason
) {
}
