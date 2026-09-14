package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.Hotel;

public record HotelResponse(
        Long id,
        String name,
        String city,
        String state,
        String country,
        String address,
        String description,
        int starRating,
        boolean active
) {

    public static HotelResponse from(Hotel hotel) {
        return new HotelResponse(hotel.getId(), hotel.getName(), hotel.getCity(), hotel.getState(), hotel.getCountry(),
                hotel.getAddress(), hotel.getDescription(), hotel.getStarRating(), hotel.isActive());
    }
}
