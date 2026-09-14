package com.grandvista.bookingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Shared JWT verification settings. This service only VALIDATES tokens issued by the
 * auth service, so it needs the same secret and expected issuer.
 */
@ConfigurationProperties(prefix = "grandvista.jwt")
public record JwtProperties(String secret, String issuer) {

    private static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not configured. See grandvista-local.properties.example");
        }
    }

    public SecretKey verificationKey() {
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
