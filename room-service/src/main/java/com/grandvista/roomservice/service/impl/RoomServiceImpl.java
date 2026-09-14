package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.PriceUpdateRequest;
import com.grandvista.roomservice.dto.RoomRequest;
import com.grandvista.roomservice.dto.RoomResponse;
import com.grandvista.roomservice.dto.RoomSearchCriteria;
import com.grandvista.roomservice.dto.RoomStatusUpdateRequest;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomStatus;
import com.grandvista.roomservice.exception.BusinessRuleException;
import com.grandvista.roomservice.exception.InvalidRequestException;
import com.grandvista.roomservice.exception.ResourceNotFoundException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.repository.RoomSpecifications;
import com.grandvista.roomservice.service.RoomService;
import com.grandvista.roomservice.util.StayPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class RoomServiceImpl implements RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceImpl.class);
    private static final Sort CATALOGUE_ORDER = Sort.by("hotel.city", "hotel.name", "basePrice", "roomNumber");

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final InventoryProperties inventoryProperties;
    private final Clock clock;

    public RoomServiceImpl(RoomRepository roomRepository, HotelRepository hotelRepository,
                           InventoryProperties inventoryProperties, Clock clock) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
        this.inventoryProperties = inventoryProperties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> searchRooms(RoomSearchCriteria criteria) {
        boolean hasCheckIn = criteria.checkIn() != null;
        boolean hasCheckOut = criteria.checkOut() != null;
        if (hasCheckIn != hasCheckOut) {
            throw new InvalidRequestException("Provide both checkIn and checkOut to search by dates");
        }
        if (hasCheckIn) {
            StayPeriod.of(criteria.checkIn(), criteria.checkOut(), LocalDate.now(clock),
                    inventoryProperties.maxStayNights());
        }
        return roomRepository.findAll(RoomSpecifications.bookableMatching(criteria, clock.instant()), CATALOGUE_ORDER)
                .stream().map(RoomResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoom(Long roomId) {
        return RoomResponse.from(findRoom(roomId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> listAllRooms() {
        return roomRepository.findAll((root, query, cb) -> cb.conjunction(), CATALOGUE_ORDER)
                .stream().map(RoomResponse::from).toList();
    }

    @Override
    @Transactional
    public RoomResponse createRoom(RoomRequest request) {
        Hotel hotel = hotelRepository.findById(request.hotelId())
                .orElseThrow(() -> new ResourceNotFoundException("Hotel " + request.hotelId() + " not found"));
        String roomNumber = request.roomNumber().trim().toUpperCase();
        if (roomRepository.existsByHotelIdAndRoomNumberIgnoreCase(hotel.getId(), roomNumber)) {
            throw new BusinessRuleException("Room " + roomNumber + " already exists in " + hotel.getName());
        }
        Room room = roomRepository.save(new Room(hotel, roomNumber, request.roomType(), request.basePrice(),
                request.capacity(), request.description()));
        log.info("Room id={} ({} {}) created in hotel id={}", room.getId(), roomNumber, room.getRoomType(), hotel.getId());
        return RoomResponse.from(room);
    }

    @Override
    @Transactional
    public RoomResponse updateRoom(Long roomId, RoomRequest request) {
        Room room = findRoom(roomId);
        String roomNumber = request.roomNumber().trim().toUpperCase();
        if (!room.getHotel().getId().equals(request.hotelId())) {
            throw new InvalidRequestException("A room cannot be moved to a different hotel");
        }
        if (!room.getRoomNumber().equalsIgnoreCase(roomNumber)
                && roomRepository.existsByHotelIdAndRoomNumberIgnoreCase(request.hotelId(), roomNumber)) {
            throw new BusinessRuleException("Room " + roomNumber + " already exists in " + room.getHotel().getName());
        }
        room.update(roomNumber, request.roomType(), request.basePrice(), request.capacity(), request.description());
        log.info("Room id={} updated", roomId);
        return RoomResponse.from(room);
    }

    @Override
    @Transactional
    public RoomResponse updateBasePrice(Long roomId, PriceUpdateRequest request) {
        Room room = findRoom(roomId);
        log.info("Room id={} base price changed {} -> {}", roomId, room.getBasePrice(), request.basePrice());
        room.setBasePrice(request.basePrice());
        return RoomResponse.from(room);
    }

    @Override
    @Transactional
    public RoomResponse updateStatus(Long roomId, RoomStatusUpdateRequest request) {
        Room room = findRoom(roomId);
        room.setStatus(request.status());
        log.info("Room id={} status set to {}", roomId, request.status());
        return RoomResponse.from(room);
    }

    @Override
    @Transactional
    public RoomResponse deactivateRoom(Long roomId) {
        return updateStatus(roomId, new RoomStatusUpdateRequest(RoomStatus.INACTIVE));
    }

    private Room findRoom(Long roomId) {
        return roomRepository.findWithHotelById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + roomId + " not found"));
    }
}
