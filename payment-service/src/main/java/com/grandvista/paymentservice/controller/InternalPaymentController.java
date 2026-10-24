package com.grandvista.paymentservice.controller;

import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.service.PaymentService;
import com.grandvista.paymentservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API used only by the Booking Service (X-Internal-Api-Key, plus the guest's JWT when
 * acting for a guest).
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
    @Operation(summary = "Process a simulated payment; the response status is SUCCESS or FAILED. "
            + "Idempotent per booking: a repeated request returns the original successful payment.")
    public PaymentResponse processPayment(@Valid @RequestBody PaymentRequest request, Authentication authentication) {
        return paymentService.processPayment(request, AuthenticatedUser.from(authentication));
    }

    @GetMapping("/booking/{bookingId}")
    @Operation(summary = "Outcome of a booking's payment (charged payment, else latest failed attempt; 404 if none). "
            + "Used to resolve a lost or timed-out payment response.")
    public PaymentResponse outcomeForBooking(@PathVariable Long bookingId) {
        return paymentService.outcomeForBooking(bookingId);
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a successful payment")
    public PaymentResponse refund(@PathVariable Long paymentId, Authentication authentication) {
        return paymentService.refund(paymentId, AuthenticatedUser.from(authentication));
    }
}
