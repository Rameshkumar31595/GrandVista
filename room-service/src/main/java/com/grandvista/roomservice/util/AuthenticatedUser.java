package com.grandvista.roomservice.util;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

/**
 * Identity of the caller: a guest/admin from a validated JWT (subject = user id, "roles" claim = authorities),
 * or {@link #SYSTEM} for a trusted service that called an {@code /internal/**} endpoint with only the
 * internal API key (the Booking Service's recovery job, which acts on no user's behalf).
 */
public record AuthenticatedUser(Long id, boolean admin) {

    /** Authority granted to a caller that proved knowledge of the internal API key but sent no JWT. */
    public static final String SERVICE_AUTHORITY = "ROLE_INTERNAL_SERVICE";

    /** Administrator-equivalent, but with no user id: it may repair state, never act as a guest. */
    public static final AuthenticatedUser SYSTEM = new AuthenticatedUser(0L, true);

    public static AuthenticatedUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(Long.valueOf(jwt.getSubject()), roles != null && roles.contains("ROLE_ADMIN"));
    }

    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return from(jwtAuthentication.getToken());
        }
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> SERVICE_AUTHORITY.equals(authority.getAuthority()))) {
            return SYSTEM;
        }
        throw new AccessDeniedException("Unsupported authentication");
    }

    public boolean isSystem() {
        return this == SYSTEM;
    }
}
