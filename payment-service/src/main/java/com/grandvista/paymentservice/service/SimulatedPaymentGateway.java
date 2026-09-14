package com.grandvista.paymentservice.service;

import com.grandvista.paymentservice.config.PaymentProperties;
import com.grandvista.paymentservice.dto.PaymentRequest;
import org.springframework.stereotype.Component;

/**
 * ACADEMIC MOCK of an external payment gateway - no real bank, card network or money is involved.
 * The outcome is deterministic so it can be demonstrated on demand:
 * <ul>
 *   <li>{@code simulateFailure = true}  -> DECLINED</li>
 *   <li>amount above the configured limit -> DECLINED</li>
 *   <li>otherwise                          -> APPROVED</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway {

    private final PaymentProperties properties;

    public SimulatedPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    public GatewayResult charge(PaymentRequest request) {
        if (Boolean.TRUE.equals(request.simulateFailure())) {
            return GatewayResult.decline("Payment declined by the simulated gateway (demo failure)");
        }
        if (request.amount().compareTo(properties.maxAmount()) > 0) {
            return GatewayResult.decline("Amount exceeds the simulated gateway limit of INR " + properties.maxAmount());
        }
        return GatewayResult.approve();
    }

    public record GatewayResult(boolean approved, String declineReason) {

        static GatewayResult approve() {
            return new GatewayResult(true, null);
        }

        static GatewayResult decline(String reason) {
            return new GatewayResult(false, reason);
        }
    }
}
