package com.grandvista.paymentservice.service.impl;

import com.grandvista.paymentservice.dto.PaymentRequest;
import com.grandvista.paymentservice.dto.PaymentResponse;
import com.grandvista.paymentservice.entity.Payment;
import com.grandvista.paymentservice.entity.PaymentStatus;
import com.grandvista.paymentservice.exception.BusinessRuleException;
import com.grandvista.paymentservice.exception.ResourceNotFoundException;
import com.grandvista.paymentservice.repository.PaymentRepository;
import com.grandvista.paymentservice.service.PaymentService;
import com.grandvista.paymentservice.service.SimulatedPaymentGateway;
import com.grandvista.paymentservice.service.SimulatedPaymentGateway.GatewayResult;
import com.grandvista.paymentservice.util.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String CURRENCY = "INR";

    private final PaymentRepository paymentRepository;
    private final SimulatedPaymentGateway gateway;
    private final Clock clock;

    public PaymentServiceImpl(PaymentRepository paymentRepository, SimulatedPaymentGateway gateway, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * Idempotent per booking. Deliberately NOT {@code @Transactional}: the insert commits inside
     * {@code save}, so a unique-constraint violation from a racing duplicate request can be caught here
     * and answered with the payment that won the race.
     */
    @Override
    public PaymentResponse processPayment(PaymentRequest request, AuthenticatedUser user) {
        if (user.isSystem()) {
            throw new AccessDeniedException("A payment must be made on behalf of a guest");
        }
        Optional<Payment> alreadyCharged = findChargedPayment(request.bookingId());
        if (alreadyCharged.isPresent()) {
            return replay(alreadyCharged.get(), request);
        }
        GatewayResult result = gateway.charge(request);
        PaymentStatus status = result.approved() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;

        Payment payment;
        try {
            payment = paymentRepository.save(new Payment(request.bookingId(), user.id(), request.amount(), CURRENCY,
                    request.method(), status, newTransactionReference(), result.declineReason()));
        } catch (DataIntegrityViolationException ex) {
            // Two requests for the same booking passed the check above; the unique constraint admitted one.
            Payment winner = findChargedPayment(request.bookingId()).orElseThrow(() -> ex);
            return replay(winner, request);
        }
        log.info("Payment id={} for booking id={} amount={} {} -> {}", payment.getId(), request.bookingId(),
                request.amount(), request.method(), status);
        return PaymentResponse.from(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse outcomeForBooking(Long bookingId) {
        Payment payment = findChargedPayment(bookingId)
                .or(() -> paymentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(bookingId,
                        Set.of(PaymentStatus.FAILED)))
                .orElseThrow(() -> new ResourceNotFoundException("No payment attempt recorded for booking " + bookingId));
        return PaymentResponse.from(payment);
    }

    private Optional<Payment> findChargedPayment(Long bookingId) {
        return paymentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(bookingId,
                Set.of(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED));
    }

    private PaymentResponse replay(Payment existing, PaymentRequest request) {
        if (existing.getAmount().compareTo(request.amount()) != 0) {
            throw new BusinessRuleException("Booking " + request.bookingId()
                    + " was already charged a different amount");
        }
        log.info("Duplicate payment request for booking id={} - returning existing payment id={} ({})",
                request.bookingId(), existing.getId(), existing.getStatus());
        return PaymentResponse.from(existing);
    }

    @Override
    @Transactional
    public PaymentResponse refund(Long paymentId, AuthenticatedUser user) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + paymentId + " not found"));
        checkAccess(payment, user);
        switch (payment.getStatus()) {
            case REFUNDED -> {
                return PaymentResponse.from(payment); // idempotent retry
            }
            case FAILED -> throw new BusinessRuleException("A failed payment cannot be refunded");
            case SUCCESS -> {
                payment.markRefunded(clock.instant());
                log.info("Payment id={} for booking id={} REFUNDED", paymentId, payment.getBookingId());
                return PaymentResponse.from(payment);
            }
            default -> throw new IllegalStateException("Unknown payment status " + payment.getStatus());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Long paymentId, AuthenticatedUser user) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + paymentId + " not found"));
        checkAccess(payment, user);
        return PaymentResponse.from(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> paymentsForBooking(Long bookingId, AuthenticatedUser user) {
        List<Payment> payments = paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
        payments.forEach(payment -> checkAccess(payment, user));
        return payments.stream().map(PaymentResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> myPayments(AuthenticatedUser user) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(user.id()).stream().map(PaymentResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> allPayments() {
        return paymentRepository.findAllByOrderByCreatedAtDesc().stream().map(PaymentResponse::from).toList();
    }

    private static void checkAccess(Payment payment, AuthenticatedUser user) {
        if (!user.admin() && !payment.getUserId().equals(user.id())) {
            throw new AccessDeniedException("Payment belongs to another user");
        }
    }

    /** e.g. GV-PAY-20260914-3F9A1C7B */
    private String newTransactionReference() {
        String date = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "GV-PAY-" + date + "-" + random;
    }
}
