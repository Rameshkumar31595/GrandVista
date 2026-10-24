package com.grandvista.paymentservice.repository;

import com.grandvista.paymentservice.entity.Payment;
import com.grandvista.paymentservice.entity.PaymentMethod;
import com.grandvista.paymentservice.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void successfulPaymentLookupIgnoresFailedAttempts() {
        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.FAILED, "GV-PAY-1"));
        assertThat(paymentRepository.existsByBookingIdAndStatus(42L, PaymentStatus.SUCCESS)).isFalse();

        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-2"));
        assertThat(paymentRepository.existsByBookingIdAndStatus(42L, PaymentStatus.SUCCESS)).isTrue();
        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(42L)).hasSize(2);
    }

    @Test
    void transactionReferenceIsUnique() {
        paymentRepository.saveAndFlush(payment(1L, PaymentStatus.SUCCESS, "GV-PAY-SAME"));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment(2L, PaymentStatus.SUCCESS, "GV-PAY-SAME")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsASecondSuccessfulChargeForTheSameBooking() {
        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-A"));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-B")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedAttemptsNeverBlockEachOtherOrALaterSuccess() {
        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.FAILED, "GV-PAY-1"));
        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.FAILED, "GV-PAY-2"));
        paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-3"));

        assertThat(paymentRepository.findByBookingIdOrderByCreatedAtDesc(42L)).hasSize(3);
    }

    @Test
    void refundedPaymentStillOccupiesTheOneChargePerBookingSlot() {
        Payment charged = paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-A"));
        charged.markRefunded(java.time.Instant.parse("2026-09-14T06:00:00Z"));
        paymentRepository.saveAndFlush(charged);

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment(42L, PaymentStatus.SUCCESS, "GV-PAY-B")))
                .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear(); // drop the rejected insert, otherwise the next query would try to flush it again
        assertThat(paymentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(42L,
                java.util.Set.of(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED))).get()
                .extracting(Payment::getStatus).isEqualTo(PaymentStatus.REFUNDED);
    }

    private static Payment payment(Long bookingId, PaymentStatus status, String reference) {
        return new Payment(bookingId, 7L, new BigDecimal("16500.00"), "INR", PaymentMethod.UPI, status, reference, null);
    }
}
