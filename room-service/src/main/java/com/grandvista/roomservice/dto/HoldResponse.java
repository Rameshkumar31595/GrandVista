package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.RoomHold;
import com.grandvista.roomservice.entity.RoomType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record HoldResponse(
        Long holdId,
        Long roomId,
        Long hotelId,
        String hotelName,
        String roomNumber,
        RoomType roomType,
        Long userId,
        LocalDate checkIn,
        LocalDate checkOut,
        int guests,
        BigDecimal totalAmount,
        HoldStatus status,
        Instant expiresAt
) {

    public static HoldResponse from(RoomHold hold) {
        return new HoldResponse(hold.getId(), hold.getRoom().getId(), hold.getRoom().getHotel().getId(),
                hold.getRoom().getHotel().getName(), hold.getRoom().getRoomNumber(), hold.getRoom().getRoomType(),
                hold.getUserId(), hold.getCheckIn(), hold.getCheckOut(), hold.getGuests(), hold.getTotalAmount(),
                hold.getStatus(), hold.getExpiresAt());
    }
}
