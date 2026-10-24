package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.repository.PricingRuleRepository;
import com.grandvista.roomservice.repository.RoomHoldRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.service.PriceCalculator;
import com.grandvista.roomservice.util.StayPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** The occupancy figure that drives demand-based pricing is derived from real holds, not from a flag. */
@ExtendWith(MockitoExtension.class)
class PricingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private static final StayPeriod STAY = new StayPeriod(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 21));

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomHoldRepository holdRepository;
    @Mock
    private PricingRuleRepository pricingRuleRepository;

    private PricingServiceImpl service;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new PricingServiceImpl(roomRepository, holdRepository, pricingRuleRepository, new PriceCalculator(),
                new InventoryProperties(15, 30, false), Clock.fixed(NOW, ZoneOffset.UTC));
        Hotel hotel = new Hotel("GrandVista Test", "Hyderabad", "Telangana", "India", "Addr", null, 4);
        ReflectionTestUtils.setField(hotel, "id", 1L);
        room = new Room(hotel, "203", RoomType.DELUXE, new BigDecimal("5000.00"), 2, null);
        ReflectionTestUtils.setField(room, "id", 10L);
        when(pricingRuleRepository.findByActiveTrue()).thenReturn(List.of(
                new PricingRule("High", PricingRuleType.OCCUPANCY, new BigDecimal("1.10"), null, null, 50, null, null, true),
                new PricingRule("Peak", PricingRuleType.OCCUPANCY, new BigDecimal("1.25"), null, null, 75, null, null, true)));
    }

    @Test
    void occupancyIsReservedRoomsOverBookableRoomsAndSelectsTheMatchingRule() {
        when(roomRepository.countByHotelIdAndStatus(1L, RoomStatus.ACTIVE)).thenReturn(4L);
        when(holdRepository.countReservedRoomsInHotel(eq(1L), eq(STAY.checkIn()), eq(STAY.checkOut()), any()))
                .thenReturn(3L); // 75 %

        PriceQuoteResponse quote = service.quote(room, STAY);

        assertThat(quote.occupancyPercent()).isEqualTo(75);
        assertThat(quote.appliedRules()).containsExactly("Peak");
        assertThat(quote.finalTotal()).isEqualByComparingTo("6250.00");
    }

    @Test
    void halfFullHotelGetsOnlyTheLowerDemandRule() {
        when(roomRepository.countByHotelIdAndStatus(1L, RoomStatus.ACTIVE)).thenReturn(4L);
        when(holdRepository.countReservedRoomsInHotel(eq(1L), any(), any(), any())).thenReturn(2L); // 50 %

        PriceQuoteResponse quote = service.quote(room, STAY);

        assertThat(quote.occupancyPercent()).isEqualTo(50);
        assertThat(quote.finalTotal()).isEqualByComparingTo("5500.00");
    }

    @Test
    void emptyHotelPaysTheBasePrice() {
        when(roomRepository.countByHotelIdAndStatus(1L, RoomStatus.ACTIVE)).thenReturn(4L);
        when(holdRepository.countReservedRoomsInHotel(eq(1L), any(), any(), any())).thenReturn(0L);

        assertThat(service.quote(room, STAY).finalTotal()).isEqualByComparingTo("5000.00");
    }

    @Test
    void hotelWithoutBookableRoomsDoesNotDivideByZero() {
        when(roomRepository.countByHotelIdAndStatus(1L, RoomStatus.ACTIVE)).thenReturn(0L);

        PriceQuoteResponse quote = service.quote(room, STAY);

        assertThat(quote.occupancyPercent()).isZero();
        assertThat(quote.finalTotal()).isEqualByComparingTo("5000.00");
    }

    @Test
    void occupancyIsCappedAtOneHundredPercent() {
        when(roomRepository.countByHotelIdAndStatus(1L, RoomStatus.ACTIVE)).thenReturn(2L);
        when(holdRepository.countReservedRoomsInHotel(eq(1L), any(), any(), any())).thenReturn(5L); // rooms later deactivated

        assertThat(service.quote(room, STAY).occupancyPercent()).isEqualTo(100);
    }
}
