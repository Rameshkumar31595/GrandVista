package com.grandvista.bookingservice.client.dto;

import java.time.LocalDate;

/** Body of Room Service POST /internal/holds. */
public record HoldRequest(Long roomId, LocalDate checkIn, LocalDate checkOut, Integer guests) {
}
