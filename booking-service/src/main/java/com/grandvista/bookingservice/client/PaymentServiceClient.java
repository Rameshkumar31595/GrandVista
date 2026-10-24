package com.grandvista.bookingservice.client;

import com.grandvista.bookingservice.client.dto.PaymentCommand;
import com.grandvista.bookingservice.client.dto.PaymentResult;
import com.grandvista.bookingservice.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

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

    /**
     * What the Payment Service recorded for a booking, used to resolve a lost or timed-out payment response.
     * Empty when no attempt was ever recorded (404); other failures propagate as exceptions.
     */
    public Optional<PaymentResult> findByBooking(Long bookingId) {
        try {
            return Optional.ofNullable(executor.execute(SERVICE_NAME, () -> restClient.get()
                    .uri("/internal/payments/booking/{bookingId}", bookingId)
                    .headers(executor.identityHeaders())
                    .retrieve()
                    .body(PaymentResult.class)));
        } catch (DownstreamServiceException ex) {
            if (ex.getStatus() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    public PaymentResult refund(Long paymentId) {
        return executor.execute(SERVICE_NAME, () -> executor.authorize(
                        restClient.post().uri("/internal/payments/{paymentId}/refund", paymentId))
                .retrieve()
                .body(PaymentResult.class));
    }
}
