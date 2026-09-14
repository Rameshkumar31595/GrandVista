package com.grandvista.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demonstration administrator settings bound from {@code grandvista.admin.*}
 * (ADMIN_NAME, ADMIN_EMAIL, ADMIN_PASSWORD).
 */
@ConfigurationProperties(prefix = "grandvista.admin")
public record AdminProperties(String name, String email, String password) {
}
