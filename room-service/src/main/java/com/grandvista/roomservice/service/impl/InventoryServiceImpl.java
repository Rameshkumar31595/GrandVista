package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.AvailabilityResponse;
import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.dto.InventorySummaryResponse;
import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomHold;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.exception.BusinessRuleException;
import com.grandvista.roomservice.exception.InvalidRequestException;
import com.grandvista.roomservice.exception.ResourceNotFoundException;
import com.grandvista.roomservice.exception.RoomUnavailableException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.RoomHoldRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.service.PricingService;
import com.grandvista.roomservice.util.AuthenticatedUser;
import com.grandvista.roomservice.util.StayPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final RoomHoldRepository holdRepository;
    private final PricingService pricingService;
    private final InventoryProperties properties;
    private final Clock clock;

    public InventoryServiceImpl(RoomRepository roomRepository, HotelRepository hotelRepository,
                                RoomHoldRepository holdRepository, PricingService pricingService,
                                InventoryProperties properties, Clock clock) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
        this.holdRepository = holdRepository;
        this.pricingService = pricingService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(Long roomId, LocalDate checkIn, LocalDate checkOut) {
        Room room = roomRepository.findWithHotelById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + roomId + " not found"));
        StayPeriod stay = validStay(checkIn, checkOut);

        if (!room.isBookable()) {
            return new AvailabilityResponse(roomId, checkIn, checkOut, stay.nights(), false,
                    "Room is currently not open for booking (" + room.getStatus() + ")");
        }
        boolean free = holdRepository.countBlockingHolds(roomId, checkIn, checkOut, clock.instant()) == 0;
        return new AvailabilityResponse(roomId, checkIn, checkOut, stay.nights(), free,
                free ? "Room is available for the selected dates" : "Room is already reserved for some of these dates");
    }

    /**
     * Concurrency strategy:
     * <ol>
     *   <li>Lock the room row with SELECT ... FOR UPDATE (pessimistic lock).</li>
     *   <li>Count overlapping blocking holds - now safe, because any competing transaction for the
     *       same room is waiting on the lock.</li>
     *   <li>Insert the new HELD row and commit, which releases the lock.</li>
     * </ol>
     * A second simultaneous request for the same room waits at step 1, then sees the committed
     * hold at step 2 and is rejected with 409. Requests for different rooms do not block each other.
     */
    @Override
    @Transactional
    public HoldResponse createHold(CreateHoldRequest request, AuthenticatedUser user) {
        StayPeriod stay = validStay(request.checkIn(), request.checkOut());
        Room room = roomRepository.findByIdForUpdate(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room " + request.roomId() + " not found"));

        if (!room.isBookable()) {
            throw new RoomUnavailableException("Room " + room.getRoomNumber() + " is not open for booking");
        }
        if (request.guests() > room.getCapacity()) {
            throw new InvalidRequestException("Room " + room.getRoomNumber() + " accommodates at most "
                    + room.getCapacity() + " guests");
        }
        Instant now = clock.instant();
        if (holdRepository.countBlockingHolds(room.getId(), stay.checkIn(), stay.checkOut(), now) > 0) {
            log.info("Availability check failed: room id={} already reserved between {} and {}",
                    room.getId(), stay.checkIn(), stay.checkOut());
            throw new RoomUnavailableException("Room " + room.getRoomNumber()
                    + " is already reserved for some of the selected dates");
        }

        PriceQuoteResponse quote = pricingService.quote(room, stay);
        RoomHold hold = holdRepository.save(new RoomHold(room, user.id(), stay.checkIn(), stay.checkOut(),
                request.guests(), quote.finalTotal(), now.plus(properties.holdMinutes(), ChronoUnit.MINUTES)));
        log.info("Hold id={} created for room id={} user id={} {}..{} amount={}",
                hold.getId(), room.getId(), user.id(), stay.checkIn(), stay.checkOut(), quote.finalTotal());
        return HoldResponse.from(hold);
    }

    @Override
    @Transactional
    public HoldResponse confirmHold(Long holdId, AuthenticatedUser user) {
        RoomHold hold = lockOwnedHold(holdId, user);
        switch (hold.getStatus()) {
            case CONFIRMED -> {
                return HoldResponse.from(hold); // idempotent retry
            }
            case RELEASED, EXPIRED -> throw new BusinessRuleException("Reservation hold " + holdId + " is "
                    + hold.getStatus() + " and can no longer be confirmed");
            case HELD -> {
                if (hold.isExpired(clock.instant())) {
                    throw new BusinessRuleException("Reservation hold " + holdId + " has expired");
                }
                hold.confirm();
                log.info("Hold id={} CONFIRMED for room id={}", holdId, hold.getRoom().getId());
                return HoldResponse.from(hold);
            }
            default -> throw new IllegalStateException("Unknown hold status " + hold.getStatus());
        }
    }

    @Override
    @Transactional
    public HoldResponse releaseHold(Long holdId, AuthenticatedUser user) {
        RoomHold hold = lockOwnedHold(holdId, user);
        if (hold.getStatus() == HoldStatus.HELD || hold.getStatus() == HoldStatus.CONFIRMED) {
            hold.release();
            log.info("Hold id={} RELEASED - room id={} dates {}..{} are free again",
                    holdId, hold.getRoom().getId(), hold.getCheckIn(), hold.getCheckOut());
        }
        return HoldResponse.from(hold);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldResponse> upcomingHoldsForRoom(Long roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new ResourceNotFoundException("Room " + roomId + " not found");
        }
        return holdRepository.findByRoomIdAndCheckOutGreaterThanEqualOrderByCheckInAsc(roomId, LocalDate.now(clock))
                .stream().map(HoldResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventorySummaryResponse> inventorySummary() {
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        return hotelRepository.findByActiveTrueOrderByCityAscNameAsc().stream()
                .map(hotel -> summarize(hotel, today, now))
                .toList();
    }

    @Override
    @Transactional
    public int expireStaleHolds() {
        return holdRepository.expireStaleHolds(clock.instant(), HoldStatus.HELD, HoldStatus.EXPIRED);
    }

    private InventorySummaryResponse summarize(Hotel hotel, LocalDate today, Instant now) {
        long total = roomRepository.countByHotelId(hotel.getId());
        long active = roomRepository.countByHotelIdAndStatus(hotel.getId(), RoomStatus.ACTIVE);
        long maintenance = roomRepository.countByHotelIdAndStatus(hotel.getId(), RoomStatus.MAINTENANCE);
        long reserved = holdRepository.countReservedRoomsInHotel(hotel.getId(), today, today.plusDays(1), now);
        int occupancy = active == 0 ? 0 : (int) Math.min(100, reserved * 100 / active);
        return new InventorySummaryResponse(hotel.getId(), hotel.getName(), hotel.getCity(), total, active,
                maintenance, reserved, occupancy);
    }

    private RoomHold lockOwnedHold(Long holdId, AuthenticatedUser user) {
        RoomHold hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation hold " + holdId + " not found"));
        if (!user.admin() && !hold.getUserId().equals(user.id())) {
            throw new AccessDeniedException("Hold belongs to another user");
        }
        return hold;
    }

    private StayPeriod validStay(LocalDate checkIn, LocalDate checkOut) {
        return StayPeriod.of(checkIn, checkOut, LocalDate.now(clock), properties.maxStayNights());
    }
}
