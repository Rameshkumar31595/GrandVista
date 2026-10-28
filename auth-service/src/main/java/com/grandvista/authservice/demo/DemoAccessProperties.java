package com.grandvista.authservice.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Reviewer access control for the demo login, bound from {@code grandvista.demo.access.*}.
 * <p>
 * Local review mode leaves {@code code} empty, so the demo login behaves exactly as before. A public (cloud) deployment
 * sets {@code required=true} and a reviewer access code: the demo endpoints then answer 401 until the reviewer types the
 * code, and repeated wrong codes are rate limited.
 *
 * @param code                 reviewer access code (DEMO_REVIEWER_ACCESS_CODE); empty = no code needed
 * @param required             refuse to start with demo mode on but no code (the cloud profile sets this)
 * @param expiresAt            optional ISO-8601 instant (DEMO_ACCESS_EXPIRES_AT) after which demo login is closed
 * @param restrictDemoAdmin    the demo administrator may not grant ROLE_ADMIN or change an administrator's role
 * @param maxFailuresPerClient wrong codes allowed per client address within {@code failureWindow}
 * @param maxFailuresTotal     wrong codes allowed from all clients together within {@code failureWindow}
 * @param failureWindow        sliding window for the two limits above
 */
@ConfigurationProperties(prefix = "grandvista.demo.access")
public record DemoAccessProperties(String code, boolean required, String expiresAt, boolean restrictDemoAdmin,
                                   @DefaultValue("5") int maxFailuresPerClient,
                                   @DefaultValue("30") int maxFailuresTotal,
                                   @DefaultValue("15m") Duration failureWindow) {
}
