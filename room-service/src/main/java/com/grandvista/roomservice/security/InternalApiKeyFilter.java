package com.grandvista.roomservice.security;

import com.grandvista.roomservice.util.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Second security layer for service-to-service endpoints ({@code /internal/**}).
 * <p>
 * The caller must present the shared {@code X-Internal-Api-Key}, which only trusted services (Booking
 * Service) know, AND either a valid user JWT (checked by Spring Security) or no Authorization header at
 * all, in which case it acts as {@link AuthenticatedUser#SYSTEM} (the Booking Service's recovery job).
 * A present-but-invalid JWT is still rejected with 401. The API Gateway never routes
 * {@code /internal/**} and strips the key header, so browsers cannot reach these endpoints through it.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    /** Request attribute set once the internal API key has been verified (see {@link #requireVerifiedKey()}). */
    static final String VERIFIED_ATTRIBUTE = InternalApiKeyFilter.class.getName() + ".KEY_VERIFIED";

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
        return !isInternalPath(request);
    }

    /**
     * Decides on the DECODED path, the way Spring MVC and the Spring Security matchers see it.
     * {@code getRequestURI()} is the raw, still percent-encoded path: "/%69nternal/..." does not start with
     * "/internal/" there, yet is routed to the internal controllers, which would have skipped this filter.
     * Fail-closed: the request counts as internal if EITHER Spring's {@link UrlPathHelper} (decoded request URI,
     * path parameters and duplicate slashes removed) OR the servlet path + path info says so.
     */
    static boolean isInternalPath(HttpServletRequest request) {
        String servletPath = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return isInternal(UrlPathHelper.defaultInstance.getPathWithinApplication(request)) || isInternal(servletPath);
    }

    private static boolean isInternal(String path) {
        return path.equals("/internal") || path.startsWith("/internal/");
    }

    /**
     * Authorization rule for {@code /internal/**}: an authenticated caller AND proof that this filter verified the
     * internal API key for the request. Tying the rule to the verification (not to a path check inside the filter)
     * means the key can never be skipped by a path the filter fails to recognise.
     */
    public static AuthorizationManager<RequestAuthorizationContext> requireVerifiedKey() {
        return (authentication, context) -> {
            Authentication caller = authentication.get();
            boolean authenticated = caller != null && caller.isAuthenticated()
                    && !(caller instanceof AnonymousAuthenticationToken);
            boolean keyVerified = context.getRequest().getAttribute(VERIFIED_ATTRIBUTE) != null;
            return new AuthorizationDecision(authenticated && keyVerified);
        };
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
        request.setAttribute(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            // A trusted service acting for itself (e.g. recovery job): no user JWT, but a valid internal key.
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority(AuthenticatedUser.SERVICE_AUTHORITY))));
        }
        chain.doFilter(request, response);
    }
}
