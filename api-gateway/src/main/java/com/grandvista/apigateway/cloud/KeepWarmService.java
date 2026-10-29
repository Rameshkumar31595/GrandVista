package com.grandvista.apigateway.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Free-tier helper (cloud profile only). Each microservice is a separate free web service that the host puts to sleep
 * after 15 minutes without inbound traffic. A sleeping service stops renewing its Eureka lease, is evicted, and then the
 * gateway has nothing to route to - and nothing would ever wake it up.
 * <p>
 * So, while real users are active, the gateway pings the services' public health URLs: once when the gateway starts
 * (it was just woken by a visitor) and then every {@code interval} as long as the last user request is younger than
 * {@code activeWindow}. When visitors leave, the pings stop and the whole system goes back to sleep - this does not
 * keep anything awake around the clock, so the monthly free instance hours are only spent while someone uses the app.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnExpression("!'${grandvista.keep-warm.urls:}'.isBlank()")
public class KeepWarmService implements WebFilter, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KeepWarmService.class);

    private final KeepWarmProperties properties;
    private final WebClient webClient;
    private final Clock clock;
    private final AtomicReference<Instant> lastUserRequest;
    private final AtomicReference<Instant> lastWakeAttempt = new AtomicReference<>(Instant.MIN);
    private final AtomicBoolean wakeRoundInFlight = new AtomicBoolean(false);
    private volatile Disposable schedule;

    @Autowired // the package-private constructor lets tests inject a WebClient and a Clock
    public KeepWarmService(KeepWarmProperties properties) {
        this(properties, WebClient.create(), Clock.systemUTC());
    }

    KeepWarmService(KeepWarmProperties properties, WebClient webClient, Clock clock) {
        this.properties = properties;
        this.webClient = webClient;
        this.clock = clock;
        // the gateway was started (or woken) because someone wanted to use it
        this.lastUserRequest = new AtomicReference<>(clock.instant());
    }

    /** Records real traffic; the platform's own health checks of the gateway do not count. */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/api/")) {
            lastUserRequest.set(clock.instant());
        }
        return chain.filter(exchange).doOnError(ex -> {
            if (exchange.getRequest().getPath().value().startsWith("/api/") && isNoInstanceError(ex)) {
                wakeServicesOnDemand();
            }
        });
    }

    /**
     * The only trigger: the load balancer found no instance for the route (it throws NotFoundException before the
     * request is sent anywhere). A generic 503 - for example one a backend itself returned - does not qualify.
     */
    static boolean isNoInstanceError(Throwable ex) {
        return ex instanceof NotFoundException;
    }

    /**
     * A request found no instance (service asleep and evicted from Eureka). Send one round of plain health pings so
     * the sleeping services start waking now instead of waiting for the next tick. Bounded twice: at most one round
     * per {@code wakeCooldown}, and never a second round while the previous one (up to 90s per ping) is still running.
     * The failed request itself is never replayed and the pings carry no client headers.
     */
    void wakeServicesOnDemand() {
        Instant now = clock.instant();
        Instant last = lastWakeAttempt.get();
        if (Duration.between(last, now).compareTo(properties.wakeCooldown()) < 0
                || !wakeRoundInFlight.compareAndSet(false, true)) {
            return;
        }
        lastWakeAttempt.set(now);
        log.info("No service instance available for a request: sending health pings to wake the services");
        pingAll()
                .doFinally(signal -> wakeRoundInFlight.set(false))
                .subscribe(null, ex -> log.info("on-demand wake ping failed ({})", ex.getClass().getSimpleName()));
    }

    boolean usersActive() {
        return Duration.between(lastUserRequest.get(), clock.instant()).compareTo(properties.activeWindow()) < 0;
    }

    Mono<Void> pingAll() {
        return Flux.fromIterable(properties.urlList())
                .flatMap(url -> webClient.get().uri(url).retrieve().toBodilessEntity()
                        .timeout(Duration.ofSeconds(90))
                        .doOnNext(response -> log.debug("keep-warm {} -> {}", url, response.getStatusCode()))
                        .onErrorResume(ex -> {
                            log.info("keep-warm {} not answering yet ({})", url, ex.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .then();
    }

    @Override
    public void start() {
        log.info("Keep-warm active for {} service(s): every {} while users were seen in the last {}",
                properties.urlList().size(), properties.interval(), properties.activeWindow());
        schedule = Flux.interval(Duration.ZERO, properties.interval())
                .filter(tick -> usersActive())
                .concatMap(tick -> pingAll())
                .subscribe();
    }

    @Override
    public void stop() {
        Disposable current = schedule;
        if (current != null) {
            current.dispose();
        }
        schedule = null;
    }

    @Override
    public boolean isRunning() {
        return schedule != null && !schedule.isDisposed();
    }
}
