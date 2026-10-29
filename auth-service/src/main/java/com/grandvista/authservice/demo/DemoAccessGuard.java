package com.grandvista.authservice.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether a demo-login request may proceed. Checked in this order:
 * <ol>
 *   <li>demo mode off → 403 (unchanged behaviour)</li>
 *   <li>access window over (DEMO_ACCESS_EXPIRES_AT reached) → 403</li>
 *   <li>too many wrong codes recently, from this client or overall → 429</li>
 *   <li>a reviewer access code is configured but the request does not carry the right one → 401</li>
 * </ol>
 * Without a configured code (local review mode) only steps 1 and 2 apply. The code is kept only as a SHA-256 digest and
 * compared in constant time; it is never logged. The failure counters are in memory (one auth-service instance), which
 * is enough to make guessing a long code hopeless; a restart resets them.
 */
@Component
public class DemoAccessGuard {

    public static final String HEADER = "X-Demo-Access-Code";
    static final int MIN_CODE_LENGTH = 12;
    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(DemoAccessGuard.class);

    private final DemoProperties demoProperties;
    private final DemoAccessProperties access;
    private final Clock clock;
    private final byte[] expectedDigest;
    private final Instant expiresAt;
    private final Map<String, Deque<Instant>> failuresByClient = new HashMap<>();
    private final Deque<Instant> allFailures = new ArrayDeque<>();

    @Autowired
    public DemoAccessGuard(DemoProperties demoProperties, DemoAccessProperties access) {
        this(demoProperties, access, Clock.systemUTC());
    }

    DemoAccessGuard(DemoProperties demoProperties, DemoAccessProperties access, Clock clock) {
        this.demoProperties = demoProperties;
        this.access = access;
        this.clock = clock;
        String code = access.code() == null ? "" : access.code().trim();
        if (demoProperties.enabled() && access.required() && code.isEmpty()) {
            throw new IllegalStateException("DEMO_MODE_ENABLED=true on a public deployment requires "
                    + "DEMO_REVIEWER_ACCESS_CODE - refusing to start with a passwordless demo administrator");
        }
        if (!code.isEmpty() && code.length() < MIN_CODE_LENGTH) {
            throw new IllegalStateException("DEMO_REVIEWER_ACCESS_CODE must be at least " + MIN_CODE_LENGTH + " characters");
        }
        this.expectedDigest = code.isEmpty() ? null : sha256(code);
        this.expiresAt = parseExpiry(access.expiresAt());
        if (demoProperties.enabled() && expectedDigest != null) {
            log.info("Demo login requires a reviewer access code{}",
                    expiresAt == null ? "" : " and closes at " + expiresAt);
        }
    }

    /** @throws DemoModeDisabledException, DemoAccessRateLimitedException or DemoAccessDeniedException */
    public void verify(String presentedCode, String clientAddress) {
        if (!demoProperties.enabled()) {
            throw new DemoModeDisabledException();
        }
        Instant now = clock.instant();
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            throw new DemoModeDisabledException("Demo access for this review has expired");
        }
        if (expectedDigest == null) {
            return;
        }
        String client = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        synchronized (this) {
            prune(now);
            Deque<Instant> mine = failuresByClient.get(client);
            if ((mine != null && mine.size() >= access.maxFailuresPerClient())
                    || allFailures.size() >= access.maxFailuresTotal()) {
                Instant oldest = mine != null && mine.size() >= access.maxFailuresPerClient()
                        ? mine.peekFirst() : allFailures.peekFirst();
                throw new DemoAccessRateLimitedException(Duration.between(now, oldest.plus(access.failureWindow())));
            }
        }
        if (presentedCode == null || presentedCode.isBlank()) {
            // Not counted as a failure: the login page asks without a code first, to learn that one is needed.
            throw new DemoAccessDeniedException("Reviewer access code required");
        }
        if (!MessageDigest.isEqual(sha256(presentedCode.trim()), expectedDigest)) {
            recordFailure(client, now);
            log.warn("Demo login: wrong reviewer access code from {}", client.replaceAll("[^0-9A-Fa-f.:]", "?"));
            throw new DemoAccessDeniedException("Reviewer access code not accepted");
        }
    }

    /** True when the demo administrator must not hand out administrator rights (public deployments). */
    public boolean restrictDemoAdmin() {
        return access.restrictDemoAdmin();
    }

    private synchronized void recordFailure(String client, Instant now) {
        if (failuresByClient.size() >= MAX_TRACKED_CLIENTS && !failuresByClient.containsKey(client)) {
            // Memory bound: past this point new addresses are only counted in the global limit.
            allFailures.addLast(now);
            return;
        }
        failuresByClient.computeIfAbsent(client, key -> new ArrayDeque<>()).addLast(now);
        allFailures.addLast(now);
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(access.failureWindow());
        while (!allFailures.isEmpty() && !allFailures.peekFirst().isAfter(cutoff)) {
            allFailures.removeFirst();
        }
        failuresByClient.values().forEach(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.removeFirst();
            }
        });
        failuresByClient.values().removeIf(Deque::isEmpty);
    }

    private static Instant parseExpiry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("DEMO_ACCESS_EXPIRES_AT must be an ISO-8601 instant, e.g. 2026-11-30T18:00:00Z", ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
