package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.AvailabilityResponse;
import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.dto.InventorySummaryResponse;
import com.grandvista.roomservice.util.AuthenticatedUser;

import java.time.LocalDate;
import java.util.List;

/**
 * Date-aware room inventory: availability checks and the reservation-hold lifecycle
 * used by the Booking Service.
 */
public interface InventoryService {

    AvailabilityResponse checkAvailability(Long roomId, LocalDate checkIn, LocalDate checkOut);

    /** Atomically verifies availability, prices the stay and blocks the dates. */
    HoldResponse createHold(CreateHoldRequest request, AuthenticatedUser user);

    /** Makes a hold permanent after successful payment. */
    HoldResponse confirmHold(Long holdId, AuthenticatedUser user);

    /** Frees the dates (payment failed or booking cancelled). Idempotent. */
    HoldResponse releaseHold(Long holdId, AuthenticatedUser user);

    List<HoldResponse> upcomingHoldsForRoom(Long roomId);

    List<InventorySummaryResponse> inventorySummary();

    int expireStaleHolds();
}
