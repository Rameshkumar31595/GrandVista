package com.grandvista.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real gateway filter chain: a request whose route has no load-balancer instance (ROOM-SERVICE is asleep and not in
 * the registry) must make the gateway send one bounded round of plain health pings, and nothing else.
 * A JDK HttpServer plays the sleeping services' public health URLs (two URLs on one stub).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "grandvista.jwt.secret=" + AuthRouteRetryIntegrationTest.SECRET,
        "grandvista.cors.allowed-origins=http://localhost:5173",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "grandvista.keep-warm.interval=1h",
        // cooldown 0 so only the in-flight guard limits rounds in these tests
        "grandvista.keep-warm.wake-cooldown=0s",
        "grandvista.auth-retry.first-backoff=10ms",
        "grandvista.auth-retry.max-backoff=10ms",
        "grandvista.auth-retry.factor=1"
})
class KeepWarmWakeIntegrationTest {

    static final AtomicInteger PINGS = new AtomicInteger();
    static final List<String> SENSITIVE_HEADERS_SEEN = new CopyOnWriteArrayList<>();
    static volatile long pingDelayMillis = 0;
    static volatile boolean startupRoundSeen = false;
    static HttpServer stub;

    static {
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                PINGS.incrementAndGet();
                for (String header : new String[] {"Authorization", "X-Internal-Api-Key", "Cookie"}) {
                    if (exchange.getRequestHeaders().getFirst(header) != null) {
                        SENSITIVE_HEADERS_SEEN.add(header);
                    }
                }
                try {
                    Thread.sleep(pingDelayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            stub.start();
            int port = stub.getAddress().getPort();
            System.setProperty("grandvista.keep-warm.urls",
                    "http://127.0.0.1:" + port + "/room/actuator/health,http://127.0.0.1:" + port + "/auth/actuator/health");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        if (!startupRoundSeen) {      // the start-up round happens once per gateway start (shared context)
            awaitPings(2);
            startupRoundSeen = true;
        }
        Thread.sleep(300);
        PINGS.set(0);
        SENSITIVE_HEADERS_SEEN.clear();
        pingDelayMillis = 0;
    }

    @AfterEach
    void tearDown() {
        pingDelayMillis = 0;
    }

    @Test
    void noInstanceForARouteWakesAllServicesOnceWithoutReplayingOrForwardingHeaders() throws Exception {
        client.get().uri("/api/rooms")
                .header("X-Internal-Api-Key", "forged")
                .header("Cookie", "a=b")
                .exchange().expectStatus().isEqualTo(503);

        awaitPings(2);
        Thread.sleep(500);
        assertThat(PINGS.get()).isEqualTo(2);                   // both configured URLs, one round
        assertThat(SENSITIVE_HEADERS_SEEN).isEmpty();           // pings never carry client or internal headers
    }

    @Test
    void aSecondFailureWhileTheRoundIsStillRunningDoesNotStartAnotherRound() throws Exception {
        pingDelayMillis = 1500;
        client.get().uri("/api/rooms").exchange().expectStatus().isEqualTo(503);
        awaitPings(2);
        client.get().uri("/api/hotels").exchange().expectStatus().isEqualTo(503);   // round still in flight
        Thread.sleep(2500);
        assertThat(PINGS.get()).isEqualTo(2);

        pingDelayMillis = 0;
        client.get().uri("/api/rooms").exchange().expectStatus().isEqualTo(503);    // previous round finished
        awaitPings(4);
    }

    @Test
    void requestsRejectedBySecurityOrNotRoutedDoNotWakeAnything() throws Exception {
        client.get().uri("/api/bookings/my").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/not-a-route").exchange();
        client.get().uri("/actuator/health").exchange();
        Thread.sleep(800);
        assertThat(PINGS.get()).isZero();
    }

    private static void awaitPings(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (PINGS.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(PINGS.get()).isGreaterThanOrEqualTo(expected);
    }
}
