package com.grandvista.bookingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grandvista.booking")
public record BookingProperties(int maxStayNights) {
}
