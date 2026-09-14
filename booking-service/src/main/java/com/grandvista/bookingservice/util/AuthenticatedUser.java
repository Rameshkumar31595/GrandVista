package com.grandvista.bookingservice.util;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Identity extracted from a validated JWT: subject = user id, "roles" claim = authorities.
 */
public record AuthenticatedUser(Long id, boolean admin) {

    public static AuthenticatedUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(Long.valueOf(jwt.getSubject()), roles != null && roles.contains("ROLE_ADMIN"));
    }
}
