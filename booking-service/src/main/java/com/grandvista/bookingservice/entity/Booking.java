package com.grandvista.bookingservice.entity;

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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A reservation. roomId, hotelId, holdId, userId and paymentId are identifiers owned by OTHER
 * services and are stored as plain values - there are no cross-database foreign keys.
 * Hotel name / room number are a snapshot taken at booking time for display purposes.
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_user", columnList = "user_id"),
        @Index(name = "idx_bookings_status", columnList = "status")
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "hotel_id", nullable = false)
    private Long hotelId;

    @Column(name = "hotel_name", nullable = false, length = 120)
    private String hotelName;

    @Column(name = "room_number", nullable = false, length = 20)
    private String roomNumber;

    @Column(name = "room_type", nullable = false, length = 20)
    private String roomType;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(name = "number_of_guests", nullable = false)
    private int numberOfGuests;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "status_reason", length = 255)
    private String statusReason;

    /** Room Service reservation hold that blocks the dates. */
    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    /** Payment must be completed before this instant, otherwise the hold lapses. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "payment_reference", length = 40)
    private String paymentReference;

    /** Optimistic-locking version: protects against lost updates on concurrent status changes. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
        // required by JPA
    }

    public Booking(Long userId, Long roomId, Long hotelId, String hotelName, String roomNumber, String roomType,
                   LocalDate checkIn, LocalDate checkOut, int numberOfGuests, BigDecimal totalAmount,
                   Long holdId, Instant holdExpiresAt) {
        this.userId = userId;
        this.roomId = roomId;
        this.hotelId = hotelId;
        this.hotelName = hotelName;
        this.roomNumber = roomNumber;
        this.roomType = roomType;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.numberOfGuests = numberOfGuests;
        this.totalAmount = totalAmount;
        this.holdId = holdId;
        this.holdExpiresAt = holdExpiresAt;
        this.status = BookingStatus.PENDING;
        this.statusReason = "Awaiting payment";
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

    public void confirm(Long paymentId, String paymentReference) {
        this.status = BookingStatus.CONFIRMED;
        this.statusReason = "Payment received and room confirmed";
        this.paymentId = paymentId;
        this.paymentReference = paymentReference;
        this.holdExpiresAt = null;
    }

    public void cancel(String reason) {
        this.status = BookingStatus.CANCELLED;
        this.statusReason = reason;
        this.holdExpiresAt = null;
    }

    public void recordPayment(Long paymentId, String paymentReference) {
        this.paymentId = paymentId;
        this.paymentReference = paymentReference;
    }

    public boolean isHoldExpired(Instant now) {
        return status == BookingStatus.PENDING && holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getHotelId() {
        return hotelId;
    }

    public String getHotelName() {
        return hotelName;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public String getRoomType() {
        return roomType;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public int getNumberOfGuests() {
        return numberOfGuests;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Long getHoldId() {
        return holdId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
