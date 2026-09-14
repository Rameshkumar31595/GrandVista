package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.RoomHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RoomHoldRepository extends JpaRepository<RoomHold, Long> {

    /**
     * Overlap rule for hotel stays with an exclusive check-out date:
     * <pre>  existing.checkIn &lt; requested.checkOut  AND  existing.checkOut &gt; requested.checkIn</pre>
     * A stay 20-23 and a stay 23-25 do NOT overlap (checkout morning / check-in afternoon).
     * Only CONFIRMED holds and HELD holds that have not expired block the room.
     */
    @Query("""
            select count(h) from RoomHold h
            where h.room.id = :roomId
              and h.checkIn < :checkOut
              and h.checkOut > :checkIn
              and (h.status = :confirmed or (h.status = :held and h.expiresAt > :now))
            """)
    long countBlockingHolds(@Param("roomId") Long roomId,
                            @Param("checkIn") LocalDate checkIn,
                            @Param("checkOut") LocalDate checkOut,
                            @Param("now") Instant now,
                            @Param("confirmed") HoldStatus confirmed,
                            @Param("held") HoldStatus held);

    default long countBlockingHolds(Long roomId, LocalDate checkIn, LocalDate checkOut, Instant now) {
        return countBlockingHolds(roomId, checkIn, checkOut, now, HoldStatus.CONFIRMED, HoldStatus.HELD);
    }

    /** Number of distinct rooms of a hotel that are reserved for at least one night of the stay. */
    @Query("""
            select count(distinct h.room.id) from RoomHold h
            where h.room.hotel.id = :hotelId
              and h.checkIn < :checkOut
              and h.checkOut > :checkIn
              and (h.status = :confirmed or (h.status = :held and h.expiresAt > :now))
            """)
    long countReservedRoomsInHotel(@Param("hotelId") Long hotelId,
                                   @Param("checkIn") LocalDate checkIn,
                                   @Param("checkOut") LocalDate checkOut,
                                   @Param("now") Instant now,
                                   @Param("confirmed") HoldStatus confirmed,
                                   @Param("held") HoldStatus held);

    default long countReservedRoomsInHotel(Long hotelId, LocalDate checkIn, LocalDate checkOut, Instant now) {
        return countReservedRoomsInHotel(hotelId, checkIn, checkOut, now, HoldStatus.CONFIRMED, HoldStatus.HELD);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from RoomHold h join fetch h.room where h.id = :id")
    Optional<RoomHold> findByIdForUpdate(@Param("id") Long id);

    List<RoomHold> findByRoomIdAndCheckOutGreaterThanEqualOrderByCheckInAsc(Long roomId, LocalDate fromDate);

    /** Housekeeping: marks unpaid holds whose time window has passed. */
    @Modifying
    @Query("update RoomHold h set h.status = :expired, h.updatedAt = :now where h.status = :held and h.expiresAt <= :now")
    int expireStaleHolds(@Param("now") Instant now,
                         @Param("held") HoldStatus held,
                         @Param("expired") HoldStatus expired);
}
