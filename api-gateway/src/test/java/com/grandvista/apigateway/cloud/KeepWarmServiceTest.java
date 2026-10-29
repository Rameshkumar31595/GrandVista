package com.grandvista.apigateway.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class KeepWarmServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final List<String> pinged = new CopyOnWriteArrayList<>();
    private final List<String> requestHeaders = new CopyOnWriteArrayList<>();
    private final WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> {
                pinged.add(request.url().toString());
                for (String h : new String[] {"Authorization", "X-Internal-Api-Key"}) {
                    if (request.headers().getFirst(h) != null) {
                        requestHeaders.add(h);
                    }
                }
                if (request.url().getHost().equals("sleeping.example")) {
                    return Mono.error(new java.io.IOException("connection refused"));
                }
                return Mono.just(ClientResponse.create(HttpStatus.OK).build());
            })
            .build();
    private final KeepWarmProperties properties = new KeepWarmProperties(
            "https://auth.example/actuator/health, https://sleeping.example/actuator/health,,",
            Duration.ofMinutes(10), Duration.ofMinutes(20), Duration.ofSeconds(60));
    private final KeepWarmService service = new KeepWarmService(properties, webClient, clock);

    @Test
    void pingsEveryConfiguredUrlAndToleratesOnesThatAreStillAsleep() {
        service.pingAll().block(Duration.ofSeconds(5));

        assertThat(pinged).containsExactlyInAnyOrder("https://auth.example/actuator/health",
                "https://sleeping.example/actuator/health");
    }

    @Test
    void staysActiveOnlyWhileRealUsersAreSeen() {
        assertThat(service.usersActive()).isTrue();          // just started = just woken by a visitor

        clock.advance(Duration.ofMinutes(21));
        assertThat(service.usersActive()).isFalse();         // nobody around: let everything sleep

        service.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health")), exchange -> Mono.empty())
                .block();
        assertThat(service.usersActive()).isFalse();         // platform health checks do not count

        service.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/rooms")), exchange -> Mono.empty())
                .block();
        assertThat(service.usersActive()).isTrue();
    }

    @Test
    void noInstanceErrorOnAnApiRequestWakesTheServicesOncePerCooldownAndNeverReplaysTheRequest() {
        var request = MockServerHttpRequest.get("/api/rooms").header("Authorization", "Bearer secret")
                .header("X-Internal-Api-Key", "k").build();
        int[] dispatches = {0};
        org.springframework.web.server.WebFilterChain failing = exchange -> {
            dispatches[0]++;
            return Mono.error(new org.springframework.cloud.gateway.support.NotFoundException("no ROOM-SERVICE"));
        };

        for (int i = 0; i < 2; i++) {
            try {
                service.filter(MockServerWebExchange.from(request), failing).block();
            } catch (RuntimeException expected) {
                // the original error still reaches the client as 503
            }
        }
        assertThat(dispatches[0]).isEqualTo(2);                 // each request dispatched exactly once
        assertThat(pinged).hasSize(2);                          // one round of pings (2 urls), second call in cooldown
        assertThat(requestHeaders).isEmpty();                   // client Authorization / internal key never forwarded

        clock.advance(Duration.ofSeconds(61));
        try {
            service.filter(MockServerWebExchange.from(request), failing).block();
        } catch (RuntimeException expected) {
            // still surfaces
        }
        assertThat(pinged).hasSize(4);
    }

    @Test
    void successesOtherErrorsAndNonApiPathsDoNotWakeAnything() {
        service.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/rooms")), exchange -> Mono.empty())
                .block();
        try {
            service.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/rooms")),
                    exchange -> Mono.error(new IllegalStateException("boom"))).block();
        } catch (RuntimeException expected) {
            // not a no-instance error
        }
        try {
            service.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health")),
                    exchange -> Mono.error(new org.springframework.cloud.gateway.support.NotFoundException("x"))).block();
        } catch (RuntimeException expected) {
            // not an /api/ path
        }
        assertThat(pinged).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
