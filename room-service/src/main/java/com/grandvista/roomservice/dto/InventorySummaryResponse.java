package com.grandvista.roomservice.dto;

/**
 * Per-hotel inventory snapshot for the admin dashboard.
 */
public record InventorySummaryResponse(
        Long hotelId,
        String hotelName,
        String city,
        long totalRooms,
        long activeRooms,
        long maintenanceRooms,
        long reservedTonight,
        int occupancyTonightPercent
) {
}
