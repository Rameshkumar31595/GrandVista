package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.exception.RoomUnavailableException;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.PricingRuleRepository;
import com.grandvista.roomservice.repository.RoomHoldRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.service.PriceCalculator;
import com.grandvista.roomservice.util.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collision-prevention proof: many users try to reserve the SAME room for the SAME dates at the
 * same moment. With the pessimistic row lock exactly one hold may be created.
 * Runs on in-memory H2 with real transactions (no PostgreSQL required).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrency;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryServiceImpl.class, PricingServiceImpl.class, PriceCalculator.class,
        ConcurrentHoldIntegrationTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentHoldIntegrationTest {

    private static final int CONCURRENT_USERS = 8;

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private RoomHoldRepository holdRepository;
    @Autowired
    private PricingRuleRepository pricingRuleRepository;

    @AfterEach
    void cleanUp() {
        holdRepository.deleteAll();
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
        pricingRuleRepository.deleteAll();
    }

    @Test
    void onlyOneOfManySimultaneousRequestsForTheSameRoomAndDatesSucceeds() throws Exception {
        Hotel hotel = hotelRepository.save(new Hotel("Concurrency Hotel", "Chennai", "Tamil Nadu", "India", "Addr", null, 5));
        Room room = roomRepository.save(new Room(hotel, "203", RoomType.DELUXE, new BigDecimal("6000"), 2, null));
        LocalDate checkIn = LocalDate.now().plusDays(10);
        CreateHoldRequest request = new CreateHoldRequest(room.getId(), checkIn, checkIn.plusDays(3), 2);

        CountDownLatch startSignal = new CountDownLatch(1);
        List<Callable<String>> attempts = new ArrayList<>();
        for (long userId = 1; userId <= CONCURRENT_USERS; userId++) {
            AuthenticatedUser user = new AuthenticatedUser(userId, false);
            attempts.add(() -> {
                startSignal.await();
                try {
                    inventoryService.createHold(request, user);
                    return "SUCCESS";
                } catch (RoomUnavailableException | PessimisticLockingFailureException rejected) {
                    return "REJECTED";
                }
            });
        }

        List<String> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS)) {
            List<Future<String>> futures = attempts.stream().map(pool::submit).toList();
            startSignal.countDown(); // release all threads at once
            for (Future<String> future : futures) {
                outcomes.add(future.get());
            }
        }

        assertThat(outcomes).filteredOn("SUCCESS"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("REJECTED"::equals).hasSize(CONCURRENT_USERS - 1);
        assertThat(holdRepository.count()).isEqualTo(1);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        InventoryProperties inventoryProperties() {
            return new InventoryProperties(15, 30, false);
        }

        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }
}
