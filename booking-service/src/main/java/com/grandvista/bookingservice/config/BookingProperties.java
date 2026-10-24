package com.grandvista.bookingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxStayNights           longest allowed stay
 * @param paymentRecoveryAfter    how long a booking may sit in PAYMENT_IN_PROGRESS before the recovery job asks the
 *                                Payment Service what really happened (must exceed the client read timeout)
 * @param cancellationRetryAfter  how long a booking may sit in CANCELLATION_PENDING before the recovery job retries
 *                                the refund / room release
 */
@ConfigurationProperties(prefix = "grandvista.booking")
public record BookingProperties(
        int maxStayNights,
        @DefaultValue("60s") Duration paymentRecoveryAfter,
        @DefaultValue("30s") Duration cancellationRetryAfter) {
}
