package com.grandvista.apigateway.cloud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cloud profile only: tells the services which visitor a request comes from, for the auth service's per-client
 * demo-login rate limit.
 * <p>
 * Behind a hosting platform the gateway's direct peer is the platform's edge proxy (a private address), which appends
 * the real visitor address as the LAST {@code X-Forwarded-For} entry. Spring Cloud Gateway drops forwarded headers
 * from proxies it does not trust, and anything earlier in the header could have been typed in by the visitor. So:
 * if the direct peer matches {@code trusted-proxies}, the last entry is used, otherwise the peer itself; the result is
 * sent as {@value #HEADER}, always overwriting a value the client sent.
 */
@Component
@ConditionalOnExpression("!'${grandvista.client-address.trusted-proxies:}'.isBlank()")
public class ClientAddressFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-GrandVista-Client";

    private final Pattern trustedProxies;

    public ClientAddressFilter(@Value("${grandvista.client-address.trusted-proxies}") String trustedProxies) {
        this.trustedProxies = Pattern.compile(trustedProxies);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = clientAddress(exchange.getRequest());
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER);
                    if (client != null) {
                        headers.set(HEADER, client);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    String clientAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        String peer = remote == null ? null
                : remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
        if (peer != null && trustedProxies.matcher(peer).matches()) {
            List<String> forwarded = request.getHeaders().getOrEmpty("X-Forwarded-For");
            if (!forwarded.isEmpty()) {
                String[] hops = String.join(",", forwarded).split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty() && last.length() <= 64) {
                    return last;
                }
            }
        }
        return peer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
