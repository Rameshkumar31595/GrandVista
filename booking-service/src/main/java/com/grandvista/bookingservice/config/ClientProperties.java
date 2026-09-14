package com.grandvista.bookingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Downstream service locations are LOGICAL Eureka names (http://ROOM-SERVICE), never host:port.
 */
@ConfigurationProperties(prefix = "grandvista.clients")
public record ClientProperties(String roomServiceUrl, String paymentServiceUrl,
                               Duration connectTimeout, Duration readTimeout) {
}
