package com.grandvista.authservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorWriterTest {

    @Test
    void distinguishesMissingExpiredAndMalformedTokens() {
        assertThat(SecurityErrorWriter.describe(new InsufficientAuthenticationException("Full authentication required")))
                .startsWith("Authentication required");
        assertThat(SecurityErrorWriter.describe(new InvalidBearerTokenException("Jwt expired at 2026-09-14T10:00:00Z")))
                .isEqualTo("JWT token has expired");
        assertThat(SecurityErrorWriter.describe(new InvalidBearerTokenException("Malformed payload")))
                .isEqualTo("JWT token is invalid or malformed");
    }
}
