package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.dto.RoomSearchCriteria;
import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomHold;
import com.grandvista.roomservice.entity.RoomStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic query for the public room search. Only the filters actually supplied are applied.
 */
public final class RoomSpecifications {

    private RoomSpecifications() {
    }

    public static Specification<Room> bookableMatching(RoomSearchCriteria criteria, Instant now) {
        return (root, query, cb) -> {
            Join<Room, Hotel> hotel = root.join("hotel");
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), RoomStatus.ACTIVE));
            predicates.add(cb.isTrue(hotel.get("active")));

            if (criteria.city() != null && !criteria.city().isBlank()) {
                predicates.add(cb.equal(cb.lower(hotel.get("city")), criteria.city().trim().toLowerCase()));
            }
            if (criteria.hotelId() != null) {
                predicates.add(cb.equal(hotel.get("id"), criteria.hotelId()));
            }
            if (criteria.roomType() != null) {
                predicates.add(cb.equal(root.get("roomType"), criteria.roomType()));
            }
            if (criteria.guests() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("capacity"), criteria.guests()));
            }
            if (criteria.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("basePrice"), criteria.maxPrice()));
            }
            if (criteria.checkIn() != null && criteria.checkOut() != null) {
                // NOT EXISTS (a blocking hold overlapping the requested stay)
                Subquery<Long> blocking = query.subquery(Long.class);
                Root<RoomHold> hold = blocking.from(RoomHold.class);
                blocking.select(hold.get("id")).where(
                        cb.equal(hold.get("room"), root),
                        cb.lessThan(hold.get("checkIn"), criteria.checkOut()),
                        cb.greaterThan(hold.get("checkOut"), criteria.checkIn()),
                        cb.or(cb.equal(hold.get("status"), HoldStatus.CONFIRMED),
                                cb.and(cb.equal(hold.get("status"), HoldStatus.HELD),
                                        cb.greaterThan(hold.get("expiresAt"), now))));
                predicates.add(cb.not(cb.exists(blocking)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
