package com.grandvista.roomservice.entity;

/**
 * Operational status of the physical room. This is NOT date availability:
 * an ACTIVE room can still be unavailable for specific dates because of holds.
 */
public enum RoomStatus {
    /** Bookable. */
    ACTIVE,
    /** Temporarily closed (repairs, cleaning). */
    MAINTENANCE,
    /** Soft-deleted by an administrator; kept so historic bookings still reference a valid room. */
    INACTIVE
}
