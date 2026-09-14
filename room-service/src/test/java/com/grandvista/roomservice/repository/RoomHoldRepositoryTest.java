package com.grandvista.roomservice.repository;

import com.grandvista.roomservice.dto.RoomSearchCriteria;
import com.grandvista.roomservice.entity.HoldStatus;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomHold;
import com.grandvista.roomservice.entity.RoomType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the date-overlap JPQL query against a real (in-memory H2) database.
 */
@DataJpaTest
class RoomHoldRepositoryTest {

    private static final LocalDate SEP_20 = LocalDate.of(2030, 9, 20);
    private static final LocalDate SEP_23 = LocalDate.of(2030, 9, 23);

    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private RoomHoldRepository holdRepository;

    private Room room203;
    private Room room204;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        Hotel hotel = hotelRepository.save(new Hotel("Test Hotel", "Hyderabad", "Telangana", "India", "Addr", null, 4));
        room203 = roomRepository.save(new Room(hotel, "203", RoomType.DELUXE, new BigDecimal("5000"), 2, null));
        room204 = roomRepository.save(new Room(hotel, "204", RoomType.DELUXE, new BigDecimal("5000"), 2, null));
    }

    @Test
    void confirmedHoldBlocksOverlappingDatesButNotAdjacentDates() {
        RoomHold hold = hold(room203, SEP_20, SEP_23, now.plus(15, ChronoUnit.MINUTES));
        hold.confirm();
        holdRepository.saveAndFlush(hold);

        assertThat(holdRepository.countBlockingHolds(room203.getId(), LocalDate.of(2030, 9, 22), LocalDate.of(2030, 9, 25), now))
                .as("specification example 22..25 overlaps 20..23").isEqualTo(1);
        assertThat(holdRepository.countBlockingHolds(room203.getId(), SEP_23, LocalDate.of(2030, 9, 25), now))
                .as("check-in on the previous guest's check-out day").isZero();
        assertThat(holdRepository.countBlockingHolds(room204.getId(), SEP_20, SEP_23, now))
                .as("other rooms are unaffected").isZero();
    }

    @Test
    void activeHeldReservationBlocksButExpiredOrReleasedDoesNot() {
        holdRepository.saveAndFlush(hold(room203, SEP_20, SEP_23, now.plus(10, ChronoUnit.MINUTES)));
        assertThat(holdRepository.countBlockingHolds(room203.getId(), SEP_20, SEP_23, now)).isEqualTo(1);

        holdRepository.deleteAll();
        holdRepository.saveAndFlush(hold(room203, SEP_20, SEP_23, now.minus(1, ChronoUnit.MINUTES)));
        assertThat(holdRepository.countBlockingHolds(room203.getId(), SEP_20, SEP_23, now))
                .as("unpaid hold past its expiry no longer blocks inventory").isZero();

        holdRepository.deleteAll();
        RoomHold released = hold(room203, SEP_20, SEP_23, now.plus(10, ChronoUnit.MINUTES));
        released.release();
        holdRepository.saveAndFlush(released);
        assertThat(holdRepository.countBlockingHolds(room203.getId(), SEP_20, SEP_23, now)).isZero();
    }

    @Test
    void expireStaleHoldsMarksOnlyPastDueHeldRows() {
        holdRepository.saveAndFlush(hold(room203, SEP_20, SEP_23, now.minus(5, ChronoUnit.MINUTES)));
        holdRepository.saveAndFlush(hold(room204, SEP_20, SEP_23, now.plus(5, ChronoUnit.MINUTES)));

        int expired = holdRepository.expireStaleHolds(now,
                HoldStatus.HELD, HoldStatus.EXPIRED);

        assertThat(expired).isEqualTo(1);
    }

    @Test
    void dateSearchExcludesReservedRooms() {
        RoomHold hold = hold(room203, SEP_20, SEP_23, null);
        hold.confirm();
        holdRepository.saveAndFlush(hold);

        RoomSearchCriteria criteria = new RoomSearchCriteria("hyderabad", null, null, 2, null,
                LocalDate.of(2030, 9, 21), LocalDate.of(2030, 9, 22));

        assertThat(roomRepository.findAll(RoomSpecifications.bookableMatching(criteria, now), Sort.by("roomNumber")))
                .extracting(Room::getRoomNumber)
                .containsExactly("204");
    }

    private static RoomHold hold(Room room, LocalDate in, LocalDate out, Instant expiresAt) {
        return new RoomHold(room, 1L, in, out, 2, new BigDecimal("15000"), expiresAt);
    }
}
