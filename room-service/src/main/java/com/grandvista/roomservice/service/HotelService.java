package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.HotelRequest;
import com.grandvista.roomservice.dto.HotelResponse;

import java.util.List;

public interface HotelService {

    List<HotelResponse> listActiveHotels();

    HotelResponse getHotel(Long hotelId);

    HotelResponse createHotel(HotelRequest request);

    HotelResponse updateHotel(Long hotelId, HotelRequest request);
}
