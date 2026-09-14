package com.grandvista.roomservice.util;

import com.grandvista.roomservice.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StayPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    /** Existing reservation: Room 203, 2026-09-20 to 2026-09-23 (from the project specification). */
    private static final StayPeriod EXISTING = new StayPeriod(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23));

    @ParameterizedTest(name = "{0} -> {1} overlaps existing 20..23 = {2}")
    @CsvSource({
            "2026-09-22, 2026-09-25, true",   // specification example: starts inside
            "2026-09-18, 2026-09-21, true",   // ends inside
            "2026-09-21, 2026-09-22, true",   // fully inside
            "2026-09-19, 2026-09-24, true",   // fully surrounds
            "2026-09-20, 2026-09-23, true",   // identical
            "2026-09-23, 2026-09-25, false",  // check-in on the check-out day is allowed
            "2026-09-17, 2026-09-20, false",  // check-out on the check-in day is allowed
            "2026-09-10, 2026-09-12, false"   // completely before
    })
    void overlapRuleTreatsCheckOutAsExclusive(LocalDate checkIn, LocalDate checkOut, boolean expected) {
        assertThat(new StayPeriod(checkIn, checkOut).overlaps(EXISTING)).isEqualTo(expected);
        assertThat(EXISTING.overlaps(new StayPeriod(checkIn, checkOut))).isEqualTo(expected);
    }

    @Test
    void reversedDateRangeIsRejected() {
        assertThatThrownBy(() -> StayPeriod.of(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 22), TODAY, 30))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("after check-in");
    }

    @Test
    void sameDayCheckOutIsRejected() {
        assertThatThrownBy(() -> StayPeriod.of(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25), TODAY, 30))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void pastCheckInIsRejected() {
        assertThatThrownBy(() -> StayPeriod.of(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 15), TODAY, 30))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("past");
    }

    @Test
    void overlyLongStayIsRejected() {
        assertThatThrownBy(() -> StayPeriod.of(TODAY, TODAY.plusDays(31), TODAY, 30))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("30 nights");
    }

    @Test
    void nightsAndNightDates() {
        StayPeriod stay = StayPeriod.of(TODAY, TODAY.plusDays(3), TODAY, 30);
        assertThat(stay.nights()).isEqualTo(3);
        assertThat(stay.nightDates()).containsExactly(TODAY, TODAY.plusDays(1), TODAY.plusDays(2));
    }
}
