package com.grandvista.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral regression tests for the auth-route Retry filter (GV-002 fix).
 *
 * A controllable ServiceInstanceListSupplier simulates "no AUTH-SERVICE instance in the
 * registry yet" (the free-tier wake-up window), and a JDK HttpServer plays the backend
 * once an instance "appears". The tests pin the safety contract: retry only happens
 * before dispatch (no instance in the registry); a response that already reached a
 * service is not replayed, and a POST is dispatched exactly once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "grandvista.jwt.secret=" + AuthRouteRetryIntegrationTest.SECRET,
        "grandvista.cors.allowed-origins=http://localhost:5173",
        "spring.cloud.loadbalancer.cache.enabled=false",
        // fast backoff in tests; production defaults live in AuthRouteConfig
        "grandvista.auth-retry.first-backoff=10ms",
        "grandvista.auth-retry.max-backoff=10ms",
        "grandvista.auth-retry.factor=1"
})
@LoadBalancerClient(name = "AUTH-SERVICE", configuration = StubAuthServiceLoadBalancerConfig.class)
class AuthRouteRetryIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYtZ2F0ZXdheS10ZXN0";

    /** Supplier lookups performed since the last reset. */
    static final AtomicInteger SUPPLIER_CALLS = new AtomicInteger();
    /** While > 0, supplier lookups report "no instance available". */
    static final AtomicInteger NO_INSTANCE_LOOKUPS = new AtomicInteger();
    /** The stub instance to hand out once the "wake-up" is over. */
    static final AtomicReference<ServiceInstance> STUB_INSTANCE = new AtomicReference<>();

    static final AtomicInteger STUB_HITS = new AtomicInteger();
    static final List<String> STUB_INTERNAL_HEADERS = new CopyOnWriteArrayList<>();
    static final AtomicInteger STUB_POSTS = new AtomicInteger();
    static volatile int stubStatus = 200;
    static volatile String stubBody = "[]";

    /** Backing supplier for StubAuthServiceLoadBalancerConfig; driven by the static controls above. */
    static ServiceInstanceListSupplier controllableSupplier() {
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "AUTH-SERVICE";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                SUPPLIER_CALLS.incrementAndGet();
                ServiceInstance instance = NO_INSTANCE_LOOKUPS.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                        ? null : STUB_INSTANCE.get();
                return Flux.just(instance == null ? List.of() : List.of(instance));
            }
        };
    }

    private HttpServer stub;
    private WebTestClient client;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        SUPPLIER_CALLS.set(0);
        NO_INSTANCE_LOOKUPS.set(0);
        STUB_HITS.set(0);
        STUB_POSTS.set(0);
        STUB_INTERNAL_HEADERS.clear();
        stubStatus = 200;
        stubBody = "[]";

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            STUB_HITS.incrementAndGet();
            STUB_INTERNAL_HEADERS.add(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            if ("POST".equals(exchange.getRequestMethod())) {
                STUB_POSTS.incrementAndGet();
            }
            exchange.getRequestBody().readAllBytes();
            byte[] body = stubBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        STUB_INSTANCE.set(new DefaultServiceInstance("stub-auth-1", "AUTH-SERVICE",
                "127.0.0.1", stub.getAddress().getPort(), false));

        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    @AfterEach
    void tearDown() {
        STUB_INSTANCE.set(null);
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void servesRequestOnceAnInstanceAppearsDuringTheRetryWindow() {
        NO_INSTANCE_LOOKUPS.set(2); // first two lookups see no instance, then the service "wakes up"

        client.get().uri("/api/auth/demo/accounts").exchange()
                .expectStatus().isOk();

        assertThat(SUPPLIER_CALLS.get()).isGreaterThanOrEqualTo(3);
        assertThat(STUB_HITS.get()).isEqualTo(1);
    }

    @Test
    void stillReturns503WhenNoInstanceEverAppears() {
        NO_INSTANCE_LOOKUPS.set(Integer.MAX_VALUE);

        client.get().uri("/api/auth/demo/accounts").exchange()
                .expectStatus().isEqualTo(503);

        // 1 initial attempt + 4 configured retries, all pre-dispatch
        assertThat(SUPPLIER_CALLS.get()).isEqualTo(5);
        assertThat(STUB_HITS.get()).isZero();
    }

    @Test
    void doesNotReplayARequestThatAlreadyReachedTheService() {
        stubStatus = 503; // auth-service itself answered: post-dispatch, must NOT be retried

        client.get().uri("/api/auth/demo/accounts").exchange()
                .expectStatus().isEqualTo(503);

        assertThat(STUB_HITS.get()).isEqualTo(1);
    }

    @Test
    void stripsTheInternalApiKeyHeaderLikeThePropertyRoutesDo() {
        client.get().uri("/api/auth/demo/accounts")
                .header("X-Internal-Api-Key", "forged-attempt").exchange()
                .expectStatus().isOk();

        assertThat(STUB_INTERNAL_HEADERS).containsExactly((String) null);
    }

    @Test
    void postLoginIsDispatchedExactlyOnce() {
        stubStatus = 401;
        stubBody = "{\"status\":401,\"message\":\"bad credentials\"}";

        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"asha@example.com\",\"password\":\"wrong\"}").exchange()
                .expectStatus().isUnauthorized();

        assertThat(STUB_POSTS.get()).isEqualTo(1);
    }
}
