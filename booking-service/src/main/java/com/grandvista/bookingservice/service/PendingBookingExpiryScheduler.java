package com.grandvista.bookingservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels PENDING bookings whose payment window has passed. No remote call is needed: the
 * Room Service hold has the same expiry time and has already stopped blocking the room.
 */
@Component
public class PendingBookingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingBookingExpiryScheduler.class);

    private final BookingService bookingService;

    public PendingBookingExpiryScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public void cancelExpiredPendingBookings() {
        int cancelled = bookingService.cancelExpiredPendingBookings();
        if (cancelled > 0) {
            log.info("Cancelled {} unpaid booking(s) whose payment window expired", cancelled);
        }
    }
}
