package com.grandvista.authservice.demo;

import java.time.Duration;

/** Too many wrong reviewer access codes (HTTP 429). */
public class DemoAccessRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public DemoAccessRateLimitedException(Duration retryAfter) {
        super("Too many attempts. Please wait and try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
