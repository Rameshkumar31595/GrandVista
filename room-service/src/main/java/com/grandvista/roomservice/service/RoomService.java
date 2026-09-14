package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.PriceUpdateRequest;
import com.grandvista.roomservice.dto.RoomRequest;
import com.grandvista.roomservice.dto.RoomResponse;
import com.grandvista.roomservice.dto.RoomSearchCriteria;
import com.grandvista.roomservice.dto.RoomStatusUpdateRequest;

import java.util.List;

public interface RoomService {

    List<RoomResponse> searchRooms(RoomSearchCriteria criteria);

    RoomResponse getRoom(Long roomId);

    List<RoomResponse> listAllRooms();

    RoomResponse createRoom(RoomRequest request);

    RoomResponse updateRoom(Long roomId, RoomRequest request);

    RoomResponse updateBasePrice(Long roomId, PriceUpdateRequest request);

    RoomResponse updateStatus(Long roomId, RoomStatusUpdateRequest request);

    /** Soft delete: sets status INACTIVE so existing bookings keep a valid room reference. */
    RoomResponse deactivateRoom(Long roomId);
}
