package com.grandvista.authservice.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Review demo-login settings bound from {@code grandvista.demo.*}.
 * <p>
 * {@code enabled} is false unless DEMO_MODE_ENABLED=true is set explicitly; the display names
 * (DEMO_GUEST1_NAME, DEMO_GUEST2_NAME, DEMO_MANAGER_NAME) are cosmetic and configurable.
 */
@ConfigurationProperties(prefix = "grandvista.demo")
public record DemoProperties(boolean enabled, String guestOneName, String guestTwoName, String managerName) {
}
