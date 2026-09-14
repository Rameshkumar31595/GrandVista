package com.grandvista.apigateway.config;

import com.grandvista.apigateway.security.GatewaySecurityErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Edge security. The gateway rejects requests that can never succeed (no/invalid token, wrong role)
 * BEFORE they reach a microservice. Every microservice still validates the JWT and enforces its own
 * rules (defence in depth), so bypassing the gateway does not bypass security.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, GatewaySecurityErrorHandler errorHandler) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        // CORS preflight requests carry no token
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()

                        // ---- AUTH ----
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .pathMatchers("/api/auth/users/**").hasRole("ADMIN")
                        .pathMatchers("/api/auth/**").authenticated()

                        // ---- ROOMS / HOTELS / PRICING ----
                        .pathMatchers(HttpMethod.GET, "/api/rooms/all", "/api/rooms/inventory-summary",
                                "/api/rooms/*/holds").hasRole("ADMIN")
                        .pathMatchers("/api/pricing-rules/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/**", "/api/hotels", "/api/hotels/**")
                        .permitAll()
                        .pathMatchers("/api/rooms/**", "/api/hotels/**").hasRole("ADMIN")

                        // ---- BOOKINGS ----
                        .pathMatchers(HttpMethod.GET, "/api/bookings").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/bookings", "/api/bookings/*/pay").hasRole("USER")
                        .pathMatchers("/api/bookings/**").authenticated()

                        // ---- PAYMENTS (read-only through the gateway) ----
                        .pathMatchers(HttpMethod.GET, "/api/payments").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/payments/**").authenticated()

                        // Anything not listed (e.g. /internal/**, /eureka/**, other actuator endpoints) is blocked.
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));
        return http.build();
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(properties.jwt().verificationKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer())));
        return decoder;
    }

    /**
     * Single, centralized CORS policy for the whole platform. Only explicitly configured origins
     * (the React dev server by default) and the headers/methods the frontend actually uses are allowed.
     * Credentials (cookies) are not allowed because authentication uses the Authorization header.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().originList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
