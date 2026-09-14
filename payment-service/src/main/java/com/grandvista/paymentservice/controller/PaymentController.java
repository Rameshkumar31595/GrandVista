package com.grandvista.paymentservice.controller;

import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.service.PaymentService;
import com.grandvista.paymentservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only payment history. Payments are CREATED only through the booking workflow
 * (Booking Service -> /internal/payments), never directly by the browser.
 */
@RestController
@RequestMapping("/payments")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/my")
    @Operation(summary = "Payments of the logged-in user")
    public List<PaymentResponse> myPayments(@AuthenticationPrincipal Jwt jwt) {
        return paymentService.myPayments(AuthenticatedUser.from(jwt));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Payment details (owner or ADMIN)")
    public PaymentResponse getPayment(@PathVariable Long paymentId, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.getPayment(paymentId, AuthenticatedUser.from(jwt));
    }

    @GetMapping("/booking/{bookingId}")
    @Operation(summary = "Payment attempts for a booking (owner or ADMIN)")
    public List<PaymentResponse> paymentsForBooking(@PathVariable Long bookingId, @AuthenticationPrincipal Jwt jwt) {
        return paymentService.paymentsForBooking(bookingId, AuthenticatedUser.from(jwt));
    }

    @GetMapping
    @Operation(summary = "All payments (ADMIN)")
    public List<PaymentResponse> allPayments() {
        return paymentService.allPayments();
    }
}
