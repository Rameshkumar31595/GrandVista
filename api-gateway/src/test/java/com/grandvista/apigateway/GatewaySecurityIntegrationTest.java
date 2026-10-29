package com.grandvista.apigateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real gateway (Netty, routes, security filter chain) with Eureka disabled.
 * No microservice is running, so a request that PASSES gateway security ends in 503 (no instance),
 * while a request BLOCKED by the gateway gets 401/403 - which is exactly what these tests check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "grandvista.jwt.secret=" + GatewaySecurityIntegrationTest.SECRET,
        "grandvista.cors.allowed-origins=http://localhost:5173",
        // keep no-instance 503s fast in tests: shrink the GV-002 auth-route retry backoff
        "grandvista.auth-retry.first-backoff=10ms",
        "grandvista.auth-retry.max-backoff=10ms",
        "grandvista.auth-retry.factor=1"
})
class GatewaySecurityIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYtZ2F0ZXdheS10ZXN0";

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void publicRoomBrowsingPassesGatewaySecurity() {
        client.get().uri("/api/rooms?city=Chennai").exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
    }

    @Test
    void protectedRouteWithoutTokenIs401WithJsonBody() {
        client.get().uri("/api/bookings/my").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.status").isEqualTo(401).jsonPath("$.path").isEqualTo("/api/bookings/my");
    }

    @Test
    void malformedTokenIsRejected() {
        client.get().uri("/api/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").isEqualTo("JWT token is invalid or malformed");
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = token("ROLE_USER", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        client.get().uri("/api/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").isEqualTo("JWT token has expired");
    }

    @Test
    void userTokenCannotReachAdminRoutes() {
        String userToken = validToken("ROLE_USER");
        client.post().uri("/api/rooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isForbidden();
        client.get().uri("/api/pricing-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isForbidden();
        client.get().uri("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminTokenPassesAdminRoutes() {
        client.get().uri("/api/rooms/all").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("ROLE_ADMIN"))
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
    }

    @Test
    void internalAndUnmappedPathsAreBlocked() {
        String userToken = validToken("ROLE_USER");
        client.post().uri("/internal/holds").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isForbidden();
        client.get().uri("/eureka/apps").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isForbidden();
        client.post().uri("/api/payments/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void demoLoginEndpointsAreReachableWithoutATokenButNothingElseUnderThem() {
        // passes gateway security -> 503 (no auth-service instance in this test), never 401/403
        client.get().uri("/api/auth/demo/accounts").exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
        client.post().uri("/api/auth/demo/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":\"ramesh\"}").exchange()
                .expectStatus().value(status -> assertThat(status).isNotIn(401, 403));

        // wrong method / sibling paths stay protected
        client.get().uri("/api/auth/demo/login").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/auth/demo/accounts").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/auth/demo/anything-else").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void corsPreflightAllowsReactDevServerOnly() {
        client.options().uri("/api/bookings")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");

        client.options().uri("/api/bookings")
                .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void corsPreflightAllowsTheReviewerAccessCodeHeaderForTheDemoLogin() {
        client.options().uri("/api/auth/demo/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-Demo-Access-Code")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    private static String validToken(String role) {
        return token(role, Instant.now(), Instant.now().plusSeconds(600));
    }

    private static String token(String role, Instant issuedAt, Instant expiresAt) {
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("grandvista-auth-service").subject("5").issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("roles", List.of(role)).build();
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
