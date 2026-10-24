package com.grandvista.bookingservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation job for the distributed booking workflow. Resolves bookings that were interrupted in the middle
 * of a payment (PAYMENT_IN_PROGRESS) and finishes refunds / room releases that did not complete
 * (CANCELLATION_PENDING). Running it on several Booking Service instances at once is safe: every step re-checks
 * the booking status under a row lock and every remote operation is idempotent.
 */
@Component
public class BookingRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingRecoveryScheduler.class);

    private final BookingService bookingService;

    public BookingRecoveryScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT60S")
    public void recoverStuckBookings() {
        int examined = bookingService.recoverStuckBookings();
        if (examined > 0) {
            log.info("Recovery pass examined {} booking(s) stuck in an intermediate state", examined);
        }
    }
}
