package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.RoomType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Optional filters for the public room search. When both dates are supplied only
 * rooms free for the whole stay are returned.
 */
public record RoomSearchCriteria(
        String city,
        Long hotelId,
        RoomType roomType,
        Integer guests,
        BigDecimal maxPrice,
        LocalDate checkIn,
        LocalDate checkOut
) {
}
