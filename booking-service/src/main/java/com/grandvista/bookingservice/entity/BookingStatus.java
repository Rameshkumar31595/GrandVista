package com.grandvista.bookingservice.entity;

/**
 * <pre>
 *  PENDING ──pay──> PAYMENT_IN_PROGRESS ──payment SUCCESS + room confirmed──> CONFIRMED
 *     │                    │                                                      │
 *     │                    ├─payment FAILED / never recorded and hold lapsed─> CANCELLED
 *     │                    └─payment SUCCESS but room not confirmed──┐            │ cancel
 *     └─user cancels / hold expires──────────────────> CANCELLED     ▼            ▼
 *                                                          CANCELLATION_PENDING ──refund + release──> CANCELLED
 * </pre>
 * The two intermediate states are persisted so that a crash, timeout or outage in the middle of the
 * distributed workflow never loses track of money or inventory: a recovery job picks them up and
 * finishes the work. The reason for a cancellation is stored in {@code statusReason}.
 */
public enum BookingStatus {
    /** Room held, waiting for the guest to pay. */
    PENDING,
    /** A payment attempt started and its outcome is not yet settled. Never expired or cancelled blindly. */
    PAYMENT_IN_PROGRESS,
    CONFIRMED,
    /** Must still refund the payment (if any) and release the room; retried until both succeed. */
    CANCELLATION_PENDING,
    CANCELLED
}
