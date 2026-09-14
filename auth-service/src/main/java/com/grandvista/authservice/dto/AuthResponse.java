package com.grandvista.authservice.dto;

import java.time.Instant;

/**
 * Returned after a successful login.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserResponse user
) {
}
