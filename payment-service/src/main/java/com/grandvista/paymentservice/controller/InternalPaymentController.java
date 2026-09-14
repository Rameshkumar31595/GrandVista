package com.grandvista.paymentservice.controller;

import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.service.PaymentService;
import com.grandvista.paymentservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API used only by the Booking Service (JWT + X-Internal-Api-Key).
 */
@RestController
@RequestMapping("/internal/payments")
@Tag(name = "Internal - payment processing (Booking Service only)")
public class InternalPaymentController {

    private final PaymentService paymentService;

    public InternalPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Process a simulated payment; the response status is SUCCESS or FAILED")
    public PaymentResponse processPayment(@Valid @RequestBody PaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.processPayment(request, AuthenticatedUser.from(jwt));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a successful payment")
    public PaymentResponse refund(@PathVariable Long paymentId, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.refund(paymentId, AuthenticatedUser.from(jwt));
    }
}
