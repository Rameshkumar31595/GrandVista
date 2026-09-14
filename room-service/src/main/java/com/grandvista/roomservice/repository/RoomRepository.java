package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    /**
     * {@code SELECT ... FOR UPDATE} on the room row. Every hold creation for the same room
     * must first obtain this lock, so two concurrent booking attempts for one room are
     * processed one after another instead of both passing the availability check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "hotel")
    List<Room> findAll(Specification<Room> spec, Sort sort);

    @EntityGraph(attributePaths = "hotel")
    Optional<Room> findWithHotelById(Long id);

    boolean existsByHotelIdAndRoomNumberIgnoreCase(Long hotelId, String roomNumber);

    long countByHotelIdAndStatus(Long hotelId, RoomStatus status);

    long countByHotelId(Long hotelId);
}
