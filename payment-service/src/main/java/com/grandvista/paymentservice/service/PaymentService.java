package com.grandvista.paymentservice.service;

import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.util.AuthenticatedUser;

import java.util.List;

public interface PaymentService {

    /**
     * Charges the booking amount through the simulated gateway and stores the transaction.
     * Idempotent per booking: repeating the request for an already charged booking returns the
     * original successful payment instead of charging again.
     */
    PaymentResponse processPayment(PaymentRequest request, AuthenticatedUser user);

    /** Refunds a successful payment (compensation or cancellation). Idempotent. */
    PaymentResponse refund(Long paymentId, AuthenticatedUser user);

    /**
     * What happened to a booking's payment: the charged (SUCCESS/REFUNDED) payment if there is one,
     * otherwise the latest FAILED attempt. Lets the Booking Service resolve a lost or timed-out response.
     *
     * @throws com.grandvista.paymentservice.exception.ResourceNotFoundException if no attempt was recorded
     */
    PaymentResponse outcomeForBooking(Long bookingId);

    PaymentResponse getPayment(Long paymentId, AuthenticatedUser user);

    List<PaymentResponse> paymentsForBooking(Long bookingId, AuthenticatedUser user);

    List<PaymentResponse> myPayments(AuthenticatedUser user);

    List<PaymentResponse> allPayments();
}
