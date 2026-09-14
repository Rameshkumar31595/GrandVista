package com.grandvista.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * JWT settings bound from {@code grandvista.jwt.*}. The secret comes from the
 * JWT_SECRET environment/local property and is validated at startup so a weak or
 * missing key stops the service instead of silently producing insecure tokens.
 */
@ConfigurationProperties(prefix = "grandvista.jwt")
public record JwtProperties(String secret, long expirationMinutes, String issuer) {

    private static final int MIN_KEY_BYTES = 32; // 256 bits, required for HMAC-SHA256

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not configured. See grandvista-local.properties.example");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalStateException("JWT_EXPIRATION_MINUTES must be positive");
        }
    }

    public SecretKey signingKey() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET must be a Base64 encoded value", ex);
        }
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
