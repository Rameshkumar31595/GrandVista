package com.grandvista.bookingservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.grandvista.bookingservice.exception.DownstreamServiceException;
import com.grandvista.bookingservice.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Common plumbing for calls to other microservices:
 * <ul>
 *   <li>propagates the caller's JWT (so downstream services know WHO the user is and enforce ownership)
 *       when there is one</li>
 *   <li>adds the X-Internal-Api-Key header (proves the call comes from a trusted service)</li>
 *   <li>translates failures: business 4xx errors keep their status and message, while
 *       unreachable / unregistered / 5xx services become a clean 503</li>
 * </ul>
 */
@Component
public class DownstreamCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(DownstreamCallExecutor.class);
    private static final Set<Integer> PROPAGATED_STATUSES = Set.of(400, 404, 409);
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    private final String internalApiKey;
    private final JsonMapper jsonMapper;

    public DownstreamCallExecutor(@Value("${grandvista.internal.api-key}") String internalApiKey, JsonMapper jsonMapper) {
        this.internalApiKey = internalApiKey;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Adds identity headers to an outgoing request: the caller's JWT (so downstream services know WHO the
     * guest is) plus the internal API key. Outside a user request (the recovery job) there is no JWT, and the
     * call goes out with the internal key alone, which the downstream services treat as the trusted system.
     */
    public <S extends RestClient.RequestHeadersSpec<S>> S authorize(S request) {
        return request.headers(identityHeaders());
    }

    /** Same headers as {@link #authorize}, for request specs whose generic type cannot be passed to it. */
    public Consumer<HttpHeaders> identityHeaders() {
        String bearerToken = currentBearerToken();
        return headers -> {
            if (bearerToken != null) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            headers.set(INTERNAL_KEY_HEADER, internalApiKey);
        };
    }

    public <T> T execute(String serviceName, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String message = extractMessage(ex.getResponseBodyAsString());
            if (PROPAGATED_STATUSES.contains(status)) {
                throw new DownstreamServiceException(serviceName, status, message);
            }
            // 401/403 here mean a configuration problem (shared secret / internal key), not a user error.
            log.error("{} returned HTTP {}: {}", serviceName, status, message);
            throw new ServiceUnavailableException(serviceName, ex);
        } catch (RestClientException | IllegalStateException ex) {
            // Connection refused, timeout, or "No instances available" from the load balancer.
            log.warn("{} call failed: {}", serviceName, ex.getMessage());
            throw new ServiceUnavailableException(serviceName, ex);
        }
    }

    /** The current user's JWT, or null when no user request is being served (background job). */
    private static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        return null;
    }

    private String extractMessage(String body) {
        try {
            ErrorBody error = jsonMapper.readValue(body, ErrorBody.class);
            return error.message() != null ? error.message() : "Downstream request failed";
        } catch (RuntimeException ex) {
            return "Downstream request failed";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String message) {
    }
}
