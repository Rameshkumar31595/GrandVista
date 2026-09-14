package com.grandvista.roomservice.entity;

/**
 * Lifecycle of a date-range reservation of a room.
 * <pre>
 *   HELD ──payment ok──> CONFIRMED ──cancel──> RELEASED
 *     │
 *     ├──payment failed / cancelled──> RELEASED
 *     └──not confirmed in time──────> EXPIRED
 * </pre>
 * Only HELD (not yet expired) and CONFIRMED holds block the dates.
 */
public enum HoldStatus {
    HELD,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
