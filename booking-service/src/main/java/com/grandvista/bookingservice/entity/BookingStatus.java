package com.grandvista.bookingservice.entity;

/**
 * <pre>
 *  PENDING ──payment SUCCESS + room confirmed──> CONFIRMED ──cancel──> CANCELLED
 *     │
 *     └──payment FAILED / hold expired / user cancels / room confirm failed──> CANCELLED
 * </pre>
 * The reason for a cancellation is stored in {@code statusReason}.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
