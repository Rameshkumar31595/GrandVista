package com.grandvista.eurekaserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Cloud setting: the registry (REST API and dashboard) needs HTTP Basic credentials; health stays open. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grandvista.eureka.auth.username=eureka",
        "grandvista.eureka.auth.password=" + RegistryBasicAuthIntegrationTest.PASSWORD,
        "grandvista.eureka.auth.required=true"
})
class RegistryBasicAuthIntegrationTest {

    static final String PASSWORD = "test/Registry+Password=";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void registryApiAndDashboardRejectAnonymousAndWrongCredentials() throws Exception {
        assertThat(get("/eureka/apps", null).statusCode()).isEqualTo(401);
        assertThat(get("/eureka/apps", null).headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).hasValueSatisfying(
                value -> assertThat(value).startsWith("Basic"));
        assertThat(get("/", null).statusCode()).isEqualTo(401);
        assertThat(get("/eureka/apps", basic("eureka", "wrong")).statusCode()).isEqualTo(401);
        // an attempt to register a fake instance without credentials is refused as well
        HttpRequest register = HttpRequest.newBuilder(URI.create(base() + "/eureka/apps/ROOM-SERVICE"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"instance\":{}}")).build();
        assertThat(http.send(register, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    }

    @Test
    void correctCredentialsReachTheRegistry() throws Exception {
        HttpResponse<String> response = get("/eureka/apps", basic("eureka", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void healthStaysOpenForThePlatformHealthCheck() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
