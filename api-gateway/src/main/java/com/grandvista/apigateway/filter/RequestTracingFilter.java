package com.grandvista.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Adds an {@code X-Request-Id} correlation id (forwarded to the microservice and returned to the
 * client) and logs one line per routed request: method, path, route, status and duration.
 * Headers such as Authorization are never logged.
 */
@Component
public class RequestTracingFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString();
        }
        String id = requestId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, id))
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, id);
        long start = System.nanoTime();

        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> {
                    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                    log.info("[{}] {} {} -> route={} status={} {}ms", id, request.getMethod(), request.getPath().value(),
                            route != null ? route.getId() : "none", exchange.getResponse().getStatusCode(),
                            (System.nanoTime() - start) / 1_000_000);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
