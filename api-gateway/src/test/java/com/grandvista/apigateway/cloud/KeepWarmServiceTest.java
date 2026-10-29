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
    private final WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> {
                pinged.add(request.url().toString());
                if (request.url().getHost().equals("sleeping.example")) {
                    return Mono.error(new java.io.IOException("connection refused"));
                }
                return Mono.just(ClientResponse.create(HttpStatus.OK).build());
            })
            .build();
    private final KeepWarmProperties properties = new KeepWarmProperties(
            "https://auth.example/actuator/health, https://sleeping.example/actuator/health,,",
            Duration.ofMinutes(10), Duration.ofMinutes(20));
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
