package com.grandvista.bookingservice.client;

import com.grandvista.bookingservice.client.dto.HoldRequest;
import com.grandvista.bookingservice.client.dto.HoldResult;
import com.grandvista.bookingservice.exception.DownstreamServiceException;
import com.grandvista.bookingservice.exception.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the HTTP contract with Room Service: identity propagation and error translation.
 */
class RoomServiceClientTest {

    private MockRestServiceServer server;
    private RoomServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ROOM-SERVICE");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RoomServiceClient(builder.build(),
                new DownstreamCallExecutor("test-internal-key", JsonMapper.builder().findAndAddModules().build()));

        Jwt jwt = Jwt.withTokenValue("user-jwt-value").header("alg", "HS256").subject("5").claim("roles", List.of("ROLE_USER")).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createHoldPropagatesUserJwtAndInternalKey() {
        server.expect(requestTo("http://ROOM-SERVICE/internal/holds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-jwt-value"))
                .andExpect(header("X-Internal-Api-Key", "test-internal-key"))
                .andRespond(withSuccess("""
                        {"holdId":300,"roomId":10,"hotelId":1,"hotelName":"GrandVista","roomNumber":"203",
                         "roomType":"DELUXE","userId":5,"checkIn":"2026-09-20","checkOut":"2026-09-23","guests":2,
                         "totalAmount":16500.00,"status":"HELD","expiresAt":"2026-09-14T06:15:00Z"}""",
                        MediaType.APPLICATION_JSON));

        HoldResult result = client.createHold(new HoldRequest(10L, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 2));

        assertThat(result.holdId()).isEqualTo(300L);
        assertThat(result.totalAmount()).isEqualByComparingTo("16500.00");
        server.verify();
    }

    @Test
    void conflictFromRoomServiceKeepsStatusAndMessage() {
        server.expect(requestTo("http://ROOM-SERVICE/internal/holds"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"status":409,"error":"Conflict","message":"Room 203 is already reserved for some of the selected dates"}"""));

        assertThatThrownBy(() -> client.createHold(new HoldRequest(10L, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 2)))
                .isInstanceOfSatisfying(DownstreamServiceException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("Room 203 is already reserved for some of the selected dates");
                });
    }

    @Test
    void serverErrorBecomesServiceUnavailable() {
        server.expect(requestTo("http://ROOM-SERVICE/internal/holds/300/confirm")).andRespond(withServerError());

        assertThatThrownBy(() -> client.confirmHold(300L)).isInstanceOf(ServiceUnavailableException.class);
    }
}
