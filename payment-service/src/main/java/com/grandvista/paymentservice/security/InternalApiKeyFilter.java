package com.grandvista.paymentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Second security layer for service-to-service endpoints ({@code /internal/**}).
 * <p>
 * The caller must present BOTH a valid user JWT (checked by Spring Security) AND the shared
 * {@code X-Internal-Api-Key}, which only trusted services (Booking Service) know. The API
 * Gateway never routes {@code /internal/**}, so browsers cannot reach these endpoints through it.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private final byte[] expectedKey;
    private final SecurityErrorWriter errorWriter;

    public InternalApiKeyFilter(String expectedKey, SecurityErrorWriter errorWriter) {
        if (expectedKey == null || expectedKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY is not configured");
        }
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        // Constant-time comparison avoids leaking the key through response timing.
        if (provided == null || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            errorWriter.writeForbidden(request, response, "Internal endpoint: trusted service credentials required");
            return;
        }
        chain.doFilter(request, response);
    }
}
