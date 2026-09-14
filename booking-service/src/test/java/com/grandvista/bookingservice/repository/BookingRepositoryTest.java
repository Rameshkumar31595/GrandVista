package com.grandvista.bookingservice.repository;

import com.grandvista.bookingservice.entity.Booking;
import com.grandvista.bookingservice.entity.BookingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BookingRepositoryTest {

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private TestEntityManager entityManager;

    @Test
    void onlyPendingBookingsWithLapsedPaymentWindowAreCancelled() {
        Instant now = Instant.now();
        Booking lapsed = bookingRepository.save(booking(now.minusSeconds(60)));
        Booking stillOpen = bookingRepository.save(booking(now.plusSeconds(600)));
        Booking confirmed = booking(now.minusSeconds(60));
        confirmed.confirm(1L, "GV-PAY-1");
        confirmed = bookingRepository.save(confirmed);
        entityManager.flush();

        int cancelled = bookingRepository.cancelExpiredPendingBookings(now, BookingStatus.PENDING,
                BookingStatus.CANCELLED, "Payment not completed in time");
        entityManager.clear();

        assertThat(cancelled).isEqualTo(1);
        assertThat(bookingRepository.findById(lapsed.getId())).get()
                .extracting(Booking::getStatus).isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookingRepository.findById(stillOpen.getId())).get()
                .extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);
        assertThat(bookingRepository.findById(confirmed.getId())).get()
                .extracting(Booking::getStatus).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void bookingsAreListedPerUser() {
        bookingRepository.save(booking(Instant.now().plusSeconds(600)));

        assertThat(bookingRepository.findByUserIdOrderByCreatedAtDesc(5L)).hasSize(1);
        assertThat(bookingRepository.findByUserIdOrderByCreatedAtDesc(6L)).isEmpty();
    }

    private static Booking booking(Instant holdExpiresAt) {
        return new Booking(5L, 10L, 1L, "GrandVista Test", "203", "DELUXE", LocalDate.of(2030, 9, 20),
                LocalDate.of(2030, 9, 23), 2, new BigDecimal("15000.00"), 300L, holdExpiresAt);
    }
}
