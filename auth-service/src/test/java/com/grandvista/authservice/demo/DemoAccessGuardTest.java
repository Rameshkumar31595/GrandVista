package com.grandvista.authservice.demo;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoAccessGuardTest {

    private static final String CODE = "review-code-0123456";
    private static final DemoProperties ON = new DemoProperties(true, "Ramesh", "Demo Guest 2", "GrandVista Manager");
    private static final DemoProperties OFF = new DemoProperties(false, "Ramesh", "Demo Guest 2", "GrandVista Manager");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));

    private static DemoAccessProperties access(String code, boolean required, String expiresAt, int perClient, int total) {
        return new DemoAccessProperties(code, required, expiresAt, true, perClient, total, Duration.ofMinutes(15));
    }

    @Test
    void localReviewModeWithoutCodeIsUnchanged() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access("", false, "", 5, 30), clock);

        assertThatCode(() -> guard.verify(null, "127.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void demoModeOffStillAnswersDisabledFirst() {
        DemoAccessGuard guard = new DemoAccessGuard(OFF, access(CODE, true, "", 5, 30), clock);

        assertThatThrownBy(() -> guard.verify(CODE, "a")).isInstanceOf(DemoModeDisabledException.class);
    }

    @Test
    void publicDeploymentRefusesToStartWithoutACodeOrWithAShortOne() {
        assertThatThrownBy(() -> new DemoAccessGuard(ON, access("", true, "", 5, 30), clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEMO_REVIEWER_ACCESS_CODE");
        assertThatThrownBy(() -> new DemoAccessGuard(ON, access("short", true, "", 5, 30), clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least");
        // demo off: no code needed, the service starts
        assertThatCode(() -> new DemoAccessGuard(OFF, access("", true, "", 5, 30), clock)).doesNotThrowAnyException();
    }

    @Test
    void codeIsRequiredAndComparedExactly() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access(CODE, true, "", 5, 30), clock);

        assertThatThrownBy(() -> guard.verify(null, "a")).isInstanceOf(DemoAccessDeniedException.class);
        assertThatThrownBy(() -> guard.verify(" ", "a")).isInstanceOf(DemoAccessDeniedException.class);
        assertThatThrownBy(() -> guard.verify(CODE.toUpperCase(), "a")).isInstanceOf(DemoAccessDeniedException.class);
        assertThatCode(() -> guard.verify(CODE, "a")).doesNotThrowAnyException();
        assertThatCode(() -> guard.verify("  " + CODE + " ", "a")).doesNotThrowAnyException();
    }

    @Test
    void accessExpiresAtTheConfiguredInstant() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access(CODE, true, "2026-11-01T12:00:00Z", 5, 30), clock);
        assertThatCode(() -> guard.verify(CODE, "a")).doesNotThrowAnyException();

        clock.advance(Duration.ofHours(2));

        assertThatThrownBy(() -> guard.verify(CODE, "a")).isInstanceOf(DemoModeDisabledException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void invalidExpiryIsAStartupError() {
        assertThatThrownBy(() -> new DemoAccessGuard(ON, access(CODE, true, "next friday", 5, 30), clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ISO-8601");
    }

    @Test
    void perClientLimitBlocksOnlyThatClientUntilTheWindowPasses() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access(CODE, true, "", 3, 100), clock);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> guard.verify("wrong-guess-xyz", "attacker"))
                    .isInstanceOf(DemoAccessDeniedException.class);
        }

        assertThatThrownBy(() -> guard.verify(CODE, "attacker")).isInstanceOf(DemoAccessRateLimitedException.class)
                .satisfies(ex -> assertThat(((DemoAccessRateLimitedException) ex).retryAfter()).isPositive());
        assertThatCode(() -> guard.verify(CODE, "reviewer")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(16));
        assertThatCode(() -> guard.verify(CODE, "attacker")).doesNotThrowAnyException();
    }

    @Test
    void globalLimitStopsGuessingFromRotatingAddresses() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access(CODE, true, "", 5, 4), clock);
        for (int i = 0; i < 4; i++) {
            String client = "spoofed-" + i;
            assertThatThrownBy(() -> guard.verify("wrong-guess-xyz", client)).isInstanceOf(DemoAccessDeniedException.class);
        }

        assertThatThrownBy(() -> guard.verify(CODE, "fresh-address")).isInstanceOf(DemoAccessRateLimitedException.class);
    }

    @Test
    void missingCodeIsNotCountedAsAFailure() {
        DemoAccessGuard guard = new DemoAccessGuard(ON, access(CODE, true, "", 2, 2), clock);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> guard.verify(null, "page-load")).isInstanceOf(DemoAccessDeniedException.class);
        }

        assertThatCode(() -> guard.verify(CODE, "page-load")).doesNotThrowAnyException();
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
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
