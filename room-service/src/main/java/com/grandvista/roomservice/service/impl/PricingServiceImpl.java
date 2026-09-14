package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.exception.ResourceNotFoundException;
import com.grandvista.roomservice.repository.PricingRuleRepository;
import com.grandvista.roomservice.repository.RoomHoldRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.service.PriceCalculator;
import com.grandvista.roomservice.service.PricingService;
import com.grandvista.roomservice.util.StayPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
public class PricingServiceImpl implements PricingService {

    private final RoomRepository roomRepository;
    private final RoomHoldRepository holdRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final PriceCalculator priceCalculator;
    private final InventoryProperties inventoryProperties;
    private final Clock clock;

    public PricingServiceImpl(RoomRepository roomRepository, RoomHoldRepository holdRepository,
                              PricingRuleRepository pricingRuleRepository, PriceCalculator priceCalculator,
                              InventoryProperties inventoryProperties, Clock clock) {
        this.roomRepository = roomRepository;
        this.holdRepository = holdRepository;
        this.pricingRuleRepository = pricingRuleRepository;
        this.priceCalculator = priceCalculator;
        this.inventoryProperties = inventoryProperties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PriceQuoteResponse quote(Long roomId, LocalDate checkIn, LocalDate checkOut) {
        Room room = roomRepository.findWithHotelById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + roomId + " not found"));
        StayPeriod stay = StayPeriod.of(checkIn, checkOut, LocalDate.now(clock), inventoryProperties.maxStayNights());
        return quote(room, stay);
    }

    @Override
    @Transactional(readOnly = true)
    public PriceQuoteResponse quote(Room room, StayPeriod stay) {
        Long hotelId = room.getHotel().getId();
        return priceCalculator.calculate(room.getId(), hotelId, room.getRoomType(), room.getBasePrice(), stay,
                pricingRuleRepository.findByActiveTrue(), occupancyPercent(hotelId, stay));
    }

    /** Share of the hotel's bookable rooms that are already reserved for part of the stay. */
    private int occupancyPercent(Long hotelId, StayPeriod stay) {
        long bookableRooms = roomRepository.countByHotelIdAndStatus(hotelId, RoomStatus.ACTIVE);
        if (bookableRooms == 0) {
            return 0;
        }
        long reserved = holdRepository.countReservedRoomsInHotel(hotelId, stay.checkIn(), stay.checkOut(), clock.instant());
        return (int) Math.min(100, reserved * 100 / bookableRooms);
    }
}
