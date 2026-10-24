package com.grandvista.bookingservice.repository;

import com.grandvista.bookingservice.entity.Booking;
import com.grandvista.bookingservice.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Booking> findAllByOrderByCreatedAtDesc();

    List<Booking> findByStatusOrderByCreatedAtDesc(BookingStatus status);

    /** Bookings parked in an intermediate state that nobody has touched since {@code cutoff} (recovery job). */
    List<Booking> findByStatusAndUpdatedAtBefore(BookingStatus status, Instant cutoff);

    /** Locks the booking row so that a double-clicked "Pay" cannot charge the same booking twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /** Unpaid bookings whose room hold has lapsed are cancelled (the Room Service already stopped blocking them). */
    @Modifying
    @Query("""
            update Booking b
               set b.status = :cancelled, b.statusReason = :reason, b.holdExpiresAt = null,
                   b.updatedAt = :now, b.version = b.version + 1
             where b.status = :pending and b.holdExpiresAt <= :now
            """)
    int cancelExpiredPendingBookings(@Param("now") Instant now,
                                     @Param("pending") BookingStatus pending,
                                     @Param("cancelled") BookingStatus cancelled,
                                     @Param("reason") String reason);
}
