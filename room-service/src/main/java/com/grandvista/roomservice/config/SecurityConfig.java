package com.grandvista.roomservice.config;

import com.grandvista.roomservice.security.InternalApiKeyFilter;
import com.grandvista.roomservice.security.SecurityErrorWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Room Service is an OAuth2-style resource server: it trusts only JWTs signed with the shared
 * secret and issued by grandvista-auth-service, and enforces roles itself (it does not rely on
 * the gateway alone).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorWriter errorWriter,
                                            @Value("${grandvista.internal.api-key}") String internalApiKey)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // ---- service-to-service (JWT + internal API key) ----
                        .requestMatchers("/internal/**").access(InternalApiKeyFilter.requireVerifiedKey())
                        // ---- admin-only read endpoints (must be declared before the public GET rules) ----
                        .requestMatchers(HttpMethod.GET, "/rooms/all", "/rooms/inventory-summary", "/rooms/*/holds")
                        .hasRole("ADMIN")
                        .requestMatchers("/pricing-rules/**").hasRole("ADMIN")
                        // ---- public catalogue browsing ----
                        .requestMatchers(HttpMethod.GET, "/hotels", "/hotels/*", "/rooms", "/rooms/*",
                                "/rooms/*/availability", "/rooms/*/price").permitAll()
                        // ---- every write on hotels/rooms is admin-only ----
                        .requestMatchers("/hotels/**", "/rooms/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new InternalApiKeyFilter(internalApiKey, errorWriter),
                        BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.verificationKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer())));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
