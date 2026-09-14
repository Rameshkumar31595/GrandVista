package com.grandvista.paymentservice.service;

import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.util.AuthenticatedUser;

import java.util.List;

public interface PaymentService {

    /** Charges the booking amount through the simulated gateway and stores the transaction. */
    PaymentResponse processPayment(PaymentRequest request, AuthenticatedUser user);

    /** Refunds a successful payment (compensation or cancellation). Idempotent. */
    PaymentResponse refund(Long paymentId, AuthenticatedUser user);

    PaymentResponse getPayment(Long paymentId, AuthenticatedUser user);

    List<PaymentResponse> paymentsForBooking(Long bookingId, AuthenticatedUser user);

    List<PaymentResponse> myPayments(AuthenticatedUser user);

    List<PaymentResponse> allPayments();
}
