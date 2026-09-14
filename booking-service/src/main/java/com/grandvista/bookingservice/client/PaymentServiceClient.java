package com.grandvista.bookingservice.client;

import com.grandvista.bookingservice.client.dto.PaymentCommand;
import com.grandvista.bookingservice.client.dto.PaymentResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls PAYMENT-SERVICE through Eureka + Spring Cloud LoadBalancer.
 */
@Component
public class PaymentServiceClient {

    static final String SERVICE_NAME = "PAYMENT-SERVICE";

    private final RestClient restClient;
    private final DownstreamCallExecutor executor;

    public PaymentServiceClient(@Qualifier("paymentServiceRestClient") RestClient restClient,
                                DownstreamCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    public PaymentResult processPayment(PaymentCommand command) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(restClient.post().uri("/internal/payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .body(PaymentResult.class));
    }

    public PaymentResult refund(Long paymentId) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(
                        restClient.post().uri("/internal/payments/{paymentId}/refund", paymentId))
                .retrieve()
                .body(PaymentResult.class));
    }
}
