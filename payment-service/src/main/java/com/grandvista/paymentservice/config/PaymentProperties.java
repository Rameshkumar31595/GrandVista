package com.grandvista.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "grandvista.payment")
public record PaymentProperties(BigDecimal maxAmount) {
}
