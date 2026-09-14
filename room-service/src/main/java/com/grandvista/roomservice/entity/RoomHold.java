package com.grandvista.roomservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Date-aware inventory record: "room R is reserved for nights [checkIn, checkOut)".
 * Availability of a room is derived from these rows - there is no boolean "available" flag.
 */
@Entity
@Table(name = "room_holds",
        indexes = @Index(name = "idx_room_holds_room_dates", columnList = "room_id, check_in, check_out"))
public class RoomHold extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, foreignKey = @ForeignKey(name = "fk_room_holds_room"))
    private Room room;

    /** Id of the user in the auth service - a plain value, NOT a cross-database foreign key. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    /** Exclusive: the guest leaves on this morning, so another guest may check in the same day. */
    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private int guests;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    /** When a HELD reservation stops blocking the room. Null once confirmed. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected RoomHold() {
        // required by JPA
    }

    public RoomHold(Room room, Long userId, LocalDate checkIn, LocalDate checkOut, int guests,
                    BigDecimal totalAmount, Instant expiresAt) {
        this.room = room;
        this.userId = userId;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.guests = guests;
        this.totalAmount = totalAmount;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.HELD;
    }

    public boolean isExpired(Instant now) {
        return status == HoldStatus.HELD && expiresAt != null && !expiresAt.isAfter(now);
    }

    public void confirm() {
        status = HoldStatus.CONFIRMED;
        expiresAt = null;
    }

    public void release() {
        status = HoldStatus.RELEASED;
    }

    public Long getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public int getGuests() {
        return guests;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
