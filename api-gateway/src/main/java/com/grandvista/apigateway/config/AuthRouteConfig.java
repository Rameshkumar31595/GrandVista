package com.grandvista.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Auth Service route. This route lives in Java (the other routes stay in application.properties)
 * because the Retry backoff settings cannot be expressed in the flat filter-args map that
 * properties files bind to.
 *
 * GV-002 fix: when no AUTH-SERVICE instance exists in the load-balancer registry (free-tier
 * wake-up), the load-balancer filter throws NotFoundException BEFORE the request is dispatched -
 * no server has seen it. Retrying only that exception means a replay (including POST
 * register/login) can never execute the request twice. No response statuses, no status series
 * and no other exception types are retried: a request that already reached a service, or a
 * connection that was already opened, is never replayed.
 *
 * The backoff (default 5s,10s,20s,20s = 55s worst case, under the 60s httpclient
 * response-timeout of the cloud profile) bridges short wake-up windows; longer outages still
 * surface as 503 and are handled by the frontend's retry UX. Tunable via
 * grandvista.auth-retry.* (tests use millisecond backoffs).
 */
@Configuration
public class AuthRouteConfig {

    @Bean
    RouteLocator authServiceRoute(RouteLocatorBuilder builder,
            @Value("${grandvista.auth-retry.retries:4}") int retries,
            @Value("${grandvista.auth-retry.first-backoff:5s}") Duration firstBackoff,
            @Value("${grandvista.auth-retry.max-backoff:20s}") Duration maxBackoff,
            @Value("${grandvista.auth-retry.factor:2}") int factor) {
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**")
                        .filters(f -> f
                                // default-filters from application.properties (RemoveRequestHeader
                                // X-Internal-Api-Key) apply only to property-defined routes, so this
                                // Java route strips the impersonation header itself.
                                .removeRequestHeader("X-Internal-Api-Key")
                                .stripPrefix(1).retry(retry -> retry
                                .setRetries(retries)
                                .setMethods(HttpMethod.GET, HttpMethod.POST)
                                // pre-dispatch only: no response status or series is ever retried
                                .setStatuses()
                                .setSeries()
                                .setExceptions(NotFoundException.class)
                                .setBackoff(firstBackoff, maxBackoff, factor, false)))
                        .uri("lb://AUTH-SERVICE"))
                .build();
    }
}
