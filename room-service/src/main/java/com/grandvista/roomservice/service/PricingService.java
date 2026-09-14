package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.util.StayPeriod;

import java.time.LocalDate;

public interface PricingService {

    /** Public price quote for a room and date range. */
    PriceQuoteResponse quote(Long roomId, LocalDate checkIn, LocalDate checkOut);

    /** Quote for an already loaded (and possibly locked) room with a validated stay. */
    PriceQuoteResponse quote(Room room, StayPeriod stay);
}
