package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.entity.RoomType;

import java.math.BigDecimal;

public record RoomResponse(
        Long id,
        Long hotelId,
        String hotelName,
        String city,
        String roomNumber,
        RoomType roomType,
        BigDecimal basePrice,
        int capacity,
        String description,
        RoomStatus status
) {

    public static RoomResponse from(Room room) {
        return new RoomResponse(room.getId(), room.getHotel().getId(), room.getHotel().getName(),
                room.getHotel().getCity(), room.getRoomNumber(), room.getRoomType(), room.getBasePrice(),
                room.getCapacity(), room.getDescription(), room.getStatus());
    }
}
