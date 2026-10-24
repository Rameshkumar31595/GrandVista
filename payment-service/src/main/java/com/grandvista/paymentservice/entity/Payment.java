package com.grandvista.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payments_transaction_reference", columnNames = "transaction_reference"),
                // At most one successful charge per booking, enforced by the database (NULLs never collide).
                @UniqueConstraint(name = "uk_payments_success_booking", columnNames = "success_booking_id")
        },
        indexes = {
                @Index(name = "idx_payments_booking", columnList = "booking_id"),
                @Index(name = "idx_payments_user", columnList = "user_id")
        })
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Booking id from the booking service - stored as a plain value, no cross-database foreign key. */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** User id from the auth service (JWT subject). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "transaction_reference", nullable = false, length = 40)
    private String transactionReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /**
     * Set to the booking id when this payment was charged successfully and stays set after a refund.
     * Together with its unique constraint it guarantees that a booking can never be charged twice,
     * even if two requests race past the application-level check.
     */
    @Column(name = "success_booking_id")
    private Long successBookingId;

    protected Payment() {
        // required by JPA
    }

    public Payment(Long bookingId, Long userId, BigDecimal amount, String currency, PaymentMethod method,
                   PaymentStatus status, String transactionReference, String failureReason) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.status = status;
        this.transactionReference = transactionReference;
        this.failureReason = failureReason;
        this.successBookingId = status == PaymentStatus.SUCCESS ? bookingId : null;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void markRefunded(Instant when) {
        status = PaymentStatus.REFUNDED;
        refundedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
