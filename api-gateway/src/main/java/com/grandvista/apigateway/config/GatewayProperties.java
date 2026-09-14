package com.grandvista.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Gateway settings: JWT verification key/issuer and allowed CORS origins.
 */
@ConfigurationProperties(prefix = "grandvista")
public record GatewayProperties(Jwt jwt, Cors cors) {

    public record Jwt(String secret, String issuer) {

        public Jwt {
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
            if (keyBytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
            }
            return new SecretKeySpec(keyBytes, "HmacSHA256");
        }
    }

    public record Cors(String allowedOrigins) {

        public List<String> originList() {
            return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }
}
