package com.grandvista.roomservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Load-balancing demonstration aid: every response carries {@code X-Instance-Id: room-service:<port>}
 * and each API request is logged, so when two Room Service instances run it is visible (in Postman
 * response headers and in the consoles) which instance served each request.
 */
@Component
public class InstanceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InstanceIdFilter.class);

    private final String instanceId;

    public InstanceIdFilter(@Value("${spring.application.name}") String applicationName,
                            @Value("${server.port}") String port) {
        this.instanceId = applicationName + ":" + port;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Instance-Id", instanceId);
        log.info("{} served {} {}", instanceId, request.getMethod(), request.getRequestURI());
        chain.doFilter(request, response);
    }
}
