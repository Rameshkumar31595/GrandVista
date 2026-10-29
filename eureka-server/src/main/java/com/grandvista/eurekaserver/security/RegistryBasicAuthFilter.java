package com.grandvista.eurekaserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HTTP Basic protection for the registry (REST API {@code /eureka/**} and the dashboard) on a public URL.
 * <p>
 * Without it anyone on the internet could register a fake "ROOM-SERVICE" instance and have the gateway send traffic
 * (including user tokens) to it, or de-register the real ones. Eureka clients send the credentials automatically when
 * they are embedded in {@code eureka.client.service-url.defaultZone} ({@code https://user:password@host/eureka/}).
 * Only the actuator health/info endpoints stay open, for the hosting platform's health check.
 * Registered only when a password is configured (see {@link RegistrySecurityConfig}); local runs are unaffected.
 */
public class RegistryBasicAuthFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public RegistryBasicAuthFilter(String username, String password) {
        this.expected = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator/health") || path.equals("/actuator/info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"GrandVista Eureka\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        byte[] presented;
        try {
            presented = Base64.getDecoder().decode(header.substring(6).trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(presented, expected);
    }
}
