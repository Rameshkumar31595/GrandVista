package com.grandvista.bookingservice.client;

import com.grandvista.bookingservice.client.dto.HoldRequest;
import com.grandvista.bookingservice.client.dto.HoldResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls ROOM-SERVICE through Eureka + Spring Cloud LoadBalancer.
 */
@Component
public class RoomServiceClient {

    static final String SERVICE_NAME = "ROOM-SERVICE";

    private final RestClient restClient;
    private final DownstreamCallExecutor executor;

    public RoomServiceClient(@Qualifier("roomServiceRestClient") RestClient restClient, DownstreamCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    /** Verifies room existence, date availability and capacity, calculates the price and blocks the dates. */
    public HoldResult createHold(HoldRequest request) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(restClient.post().uri("/internal/holds"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(HoldResult.class));
    }

    public HoldResult confirmHold(Long holdId) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(
                        restClient.post().uri("/internal/holds/{holdId}/confirm", holdId))
                .retrieve()
                .body(HoldResult.class));
    }

    public HoldResult releaseHold(Long holdId) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(
                        restClient.post().uri("/internal/holds/{holdId}/release", holdId))
                .retrieve()
                .body(HoldResult.class));
    }
}
