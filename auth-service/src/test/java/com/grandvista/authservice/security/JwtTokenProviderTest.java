package com.grandvista.authservice.security;

import com.grandvista.authservice.config.JwtConfig;
import com.grandvista.authservice.config.JwtProperties;
import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef-test-only".getBytes());

    private final JwtProperties properties = new JwtProperties(SECRET, 60, "grandvista-auth-service");
    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtEncoder encoder = ReflectionTestUtils.invokeMethod(jwtConfig, "jwtEncoder", properties);
    private final JwtDecoder decoder = ReflectionTestUtils.invokeMethod(jwtConfig, "jwtDecoder", properties);

    @Test
    void issuedTokenContainsSubjectRolesAndExpiry() {
        JwtTokenProvider provider = new JwtTokenProvider(encoder, properties);

        JwtTokenProvider.IssuedToken token = provider.issueToken(user(7L, Role.ROLE_ADMIN));
        Jwt jwt = decoder.decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user7@grandvista.com");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("grandvista-auth-service");
        assertThat(jwt.getExpiresAt()).isEqualTo(token.expiresAt());
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void expiredTokenIsRejected() {
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        JwtTokenProvider provider = new JwtTokenProvider(encoder, properties, Clock.fixed(twoHoursAgo, ZoneOffset.UTC));

        String token = provider.issueToken(user(1L, Role.ROLE_USER)).value();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtTokenProvider provider = new JwtTokenProvider(encoder, properties);
        String token = provider.issueToken(user(1L, Role.ROLE_USER)).value();

        // Swap the payload for one claiming ROLE_ADMIN while keeping the original signature.
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("ROLE_USER", "ROLE_ADMIN").getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        String otherSecret = Base64.getEncoder().encodeToString("another-secret-key-with-32-bytes-or-more!".getBytes());
        JwtProperties otherProps = new JwtProperties(otherSecret, 60, "grandvista-auth-service");
        JwtEncoder otherEncoder = ReflectionTestUtils.invokeMethod(jwtConfig, "jwtEncoder", otherProps);
        String token = new JwtTokenProvider(otherEncoder, otherProps).issueToken(user(1L, Role.ROLE_USER)).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void weakSecretIsRejectedAtStartup() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes());
        JwtProperties weak = new JwtProperties(shortSecret, 60, "issuer");

        assertThatThrownBy(weak::signingKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static User user(Long id, Role role) {
        User user = new User("User " + id, "user" + id + "@grandvista.com", "hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
