package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomHold;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.exception.BusinessRuleException;
import com.grandvista.roomservice.exception.InvalidRequestException;
import com.grandvista.roomservice.exception.RoomUnavailableException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.RoomHoldRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.service.PricingService;
import com.grandvista.roomservice.util.AuthenticatedUser;
import com.grandvista.roomservice.util.StayPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 20);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 23);
    private static final AuthenticatedUser USER = new AuthenticatedUser(5L, false);

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private HotelRepository hotelRepository;
    @Mock
    private RoomHoldRepository holdRepository;
    @Mock
    private PricingService pricingService;

    private InventoryServiceImpl service;
    private Room room;

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl(roomRepository, hotelRepository, holdRepository, pricingService,
                new InventoryProperties(15, 30, false), Clock.fixed(NOW, ZoneOffset.UTC));
        Hotel hotel = new Hotel("GrandVista Test", "Hyderabad", "Telangana", "India", "Addr", null, 4);
        ReflectionTestUtils.setField(hotel, "id", 1L);
        room = new Room(hotel, "203", RoomType.DELUXE, new BigDecimal("5000.00"), 2, null);
        ReflectionTestUtils.setField(room, "id", 10L);
    }

    @Test
    void createHoldRejectsOverlappingReservation() {
        when(roomRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
        when(holdRepository.countBlockingHolds(10L, CHECK_IN, CHECK_OUT, NOW)).thenReturn(1L);

        assertThatThrownBy(() -> service.createHold(new CreateHoldRequest(10L, CHECK_IN, CHECK_OUT, 2), USER))
                .isInstanceOf(RoomUnavailableException.class);
        verify(holdRepository, never()).save(any());
    }

    @Test
    void createHoldRejectsTooManyGuests() {
        when(roomRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.createHold(new CreateHoldRequest(10L, CHECK_IN, CHECK_OUT, 3), USER))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 2 guests");
    }

    @Test
    void createHoldRejectsRoomUnderMaintenance() {
        room.setStatus(RoomStatus.MAINTENANCE);
        when(roomRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.createHold(new CreateHoldRequest(10L, CHECK_IN, CHECK_OUT, 2), USER))
                .isInstanceOf(RoomUnavailableException.class);
    }

    @Test
    void createHoldStoresServerCalculatedPriceAndExpiry() {
        when(roomRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(room));
        when(holdRepository.countBlockingHolds(10L, CHECK_IN, CHECK_OUT, NOW)).thenReturn(0L);
        when(pricingService.quote(eq(room), any(StayPeriod.class))).thenReturn(quote("16500.00"));
        when(holdRepository.save(any(RoomHold.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HoldResponse response = service.createHold(new CreateHoldRequest(10L, CHECK_IN, CHECK_OUT, 2), USER);

        ArgumentCaptor<RoomHold> saved = ArgumentCaptor.forClass(RoomHold.class);
        verify(holdRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(HoldStatus.HELD);
        assertThat(saved.getValue().getUserId()).isEqualTo(5L);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(15, ChronoUnit.MINUTES));
        assertThat(response.totalAmount()).isEqualByComparingTo("16500.00");
    }

    @Test
    void confirmingAnExpiredHoldFails() {
        RoomHold hold = new RoomHold(room, 5L, CHECK_IN, CHECK_OUT, 2, new BigDecimal("15000"), NOW.minusSeconds(1));
        when(holdRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.confirmHold(99L, USER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void confirmTurnsHeldIntoConfirmedAndIsIdempotent() {
        RoomHold hold = new RoomHold(room, 5L, CHECK_IN, CHECK_OUT, 2, new BigDecimal("15000"), NOW.plusSeconds(60));
        when(holdRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(hold));

        assertThat(service.confirmHold(99L, USER).status()).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(service.confirmHold(99L, USER).status()).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(hold.getExpiresAt()).isNull();
    }

    @Test
    void releaseIsIdempotent() {
        RoomHold hold = new RoomHold(room, 5L, CHECK_IN, CHECK_OUT, 2, new BigDecimal("15000"), NOW.plusSeconds(60));
        when(holdRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(hold));

        assertThat(service.releaseHold(99L, USER).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(service.releaseHold(99L, USER).status()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    void anotherUserCannotConfirmOrReleaseTheHold() {
        RoomHold hold = new RoomHold(room, 5L, CHECK_IN, CHECK_OUT, 2, new BigDecimal("15000"), NOW.plusSeconds(60));
        when(holdRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(hold));
        AuthenticatedUser intruder = new AuthenticatedUser(6L, false);

        assertThatThrownBy(() -> service.releaseHold(99L, intruder)).isInstanceOf(AccessDeniedException.class);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.HELD);
    }

    private static PriceQuoteResponse quote(String total) {
        return new PriceQuoteResponse(10L, CHECK_IN, CHECK_OUT, 3, "INR", new BigDecimal("5000.00"),
                new BigDecimal("15000.00"), 60, new BigDecimal("1.10"), List.of("High demand"), List.of(),
                new BigDecimal(total).subtract(new BigDecimal("15000.00")), new BigDecimal(total));
    }

    @Test
    void trustedServiceCannotCreateAHoldBecauseItActsForNoGuest() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createHold(
                        new com.grandvista.roomservice.dto.CreateHoldRequest(10L, CHECK_IN, CHECK_OUT, 2),
                        AuthenticatedUser.SYSTEM))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(roomRepository, holdRepository);
    }
}
