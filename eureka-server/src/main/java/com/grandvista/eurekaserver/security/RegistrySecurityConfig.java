package com.grandvista.eurekaserver.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Turns on {@link RegistryBasicAuthFilter} when {@code grandvista.eureka.auth.password} (EUREKA_PASSWORD) is set.
 * The cloud profile sets {@code grandvista.eureka.auth.required=true}, so a public registry can never start open.
 */
@Configuration
public class RegistrySecurityConfig {

    @Bean
    FilterRegistrationBean<RegistryBasicAuthFilter> registryBasicAuthFilter(
            @Value("${grandvista.eureka.auth.username:eureka}") String username,
            @Value("${grandvista.eureka.auth.password:}") String password,
            @Value("${grandvista.eureka.auth.required:false}") boolean required) {
        if (password.isBlank()) {
            if (required) {
                throw new IllegalStateException("EUREKA_PASSWORD is required on a public deployment - refusing to start "
                        + "an open service registry");
            }
            FilterRegistrationBean<RegistryBasicAuthFilter> disabled = new FilterRegistrationBean<>(
                    new RegistryBasicAuthFilter(username, ""));
            disabled.setEnabled(false);
            return disabled;
        }
        if (password.length() < 16) {
            throw new IllegalStateException("EUREKA_PASSWORD must be at least 16 characters");
        }
        FilterRegistrationBean<RegistryBasicAuthFilter> registration = new FilterRegistrationBean<>(
                new RegistryBasicAuthFilter(username, password));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
