package com.grandvista.apigateway.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressFilterTest {

    private final ClientAddressFilter filter = new ClientAddressFilter("10\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*");

    private String forwardedClient(String peer, String... xff) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/auth/demo/accounts")
                .remoteAddress(new InetSocketAddress(peer, 40000))
                .header(ClientAddressFilter.HEADER, "1.2.3.4");          // forged by the visitor
        for (String value : xff) {
            request.header("X-Forwarded-For", value);
        }
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request), exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();
        return forwarded.get().getRequest().getHeaders().getFirst(ClientAddressFilter.HEADER);
    }

    @Test
    void behindTheTrustedEdgeProxyTheAddressItAppendedIsUsed() {
        assertThat(forwardedClient("10.0.0.7", "198.51.100.9")).isEqualTo("198.51.100.9");
        // anything the visitor put in front is ignored
        assertThat(forwardedClient("10.0.0.7", "6.6.6.6, 198.51.100.9")).isEqualTo("198.51.100.9");
        assertThat(forwardedClient("172.20.0.1", "6.6.6.6", "198.51.100.9")).isEqualTo("198.51.100.9");
    }

    @Test
    void anUntrustedPeerIsTheClientItselfAndCannotForgeItsAddress() {
        assertThat(forwardedClient("203.0.113.5", "6.6.6.6")).isEqualTo("203.0.113.5");
        assertThat(forwardedClient("203.0.113.5")).isEqualTo("203.0.113.5");
    }

    @Test
    void aTrustedPeerWithoutForwardedHeaderIsUsedAsIs() {
        assertThat(forwardedClient("10.0.0.7")).isEqualTo("10.0.0.7");
    }
}
