package com.grandvista.roomservice.service.impl;

import com.grandvista.roomservice.config.InventoryProperties;
import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomHold;
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
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same collision-prevention proof as {@link ConcurrentHoldIntegrationTest}, but against a REAL PostgreSQL
 * server, because the double-booking guarantee rests on {@code SELECT ... FOR UPDATE} behaviour that H2 only
 * imitates.
 * <p>
 * Skipped unless a disposable PostgreSQL database is supplied:
 * <pre>
 *   GV_TEST_PG_URL=jdbc:postgresql://localhost:5543/grandvista_room_test_db
 *   GV_TEST_PG_USER=...   GV_TEST_PG_PASSWORD=...
 * </pre>
 * Safety: the schema is created with {@code create-drop}, so the test refuses to run unless the database name ends
 * in {@code _test_db}; it can never touch a real GrandVista database.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${GV_TEST_PG_URL:jdbc:postgresql://localhost:1/skipped_test_db}",
        "spring.datasource.username=${GV_TEST_PG_USER:unused}",
        "spring.datasource.password=${GV_TEST_PG_PASSWORD:unused}",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryServiceImpl.class, PricingServiceImpl.class, PriceCalculator.class,
        ConcurrentHoldPostgresIntegrationTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentHoldPostgresIntegrationTest {

    private static final int CONCURRENT_USERS = 16;

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

    @BeforeAll
    static void requireDisposablePostgres() {
        String url = System.getenv("GV_TEST_PG_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql:"),
                "GV_TEST_PG_URL not set - PostgreSQL concurrency test skipped");
        assumeTrue(url.endsWith("_test_db"),
                "Refusing to run: the database name must end in _test_db (the test recreates its schema)");
    }

    @AfterEach
    void cleanUp() {
        holdRepository.deleteAll();
        roomRepository.deleteAll();
        hotelRepository.deleteAll();
        pricingRuleRepository.deleteAll();
    }

    @Test
    void onlyOneOfManySimultaneousRequestsForTheSameRoomAndDatesSucceeds() throws Exception {
        Room room = newRoom("203");
        LocalDate checkIn = LocalDate.now().plusDays(10);
        CreateHoldRequest request = new CreateHoldRequest(room.getId(), checkIn, checkIn.plusDays(3), 2);

        List<String> outcomes = runConcurrently(userId -> () -> attempt(request, userId));

        assertThat(outcomes).filteredOn("SUCCESS"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("REJECTED"::equals).hasSize(CONCURRENT_USERS - 1);
        assertThat(holdRepository.count()).isEqualTo(1);
    }

    @Test
    void overlappingButNotIdenticalWindowsNeverProduceOverlappingHolds() throws Exception {
        Room room = newRoom("301");
        LocalDate base = LocalDate.now().plusDays(20);

        // Users try staggered 3-night windows that overlap pairwise (day 0-3, 1-4, 2-5, ... ).
        List<String> outcomes = runConcurrently(userId -> {
            LocalDate checkIn = base.plusDays(userId % 8);
            CreateHoldRequest request = new CreateHoldRequest(room.getId(), checkIn, checkIn.plusDays(3), 2);
            return () -> attempt(request, userId);
        });

        List<RoomHold> holds = holdRepository.findAll();
        assertThat(outcomes).filteredOn("SUCCESS"::equals).hasSize(holds.size());
        for (int i = 0; i < holds.size(); i++) {
            for (int j = i + 1; j < holds.size(); j++) {
                RoomHold a = holds.get(i);
                RoomHold b = holds.get(j);
                boolean overlap = a.getCheckIn().isBefore(b.getCheckOut()) && a.getCheckOut().isAfter(b.getCheckIn());
                assertThat(overlap).as("holds %s..%s and %s..%s must not overlap",
                        a.getCheckIn(), a.getCheckOut(), b.getCheckIn(), b.getCheckOut()).isFalse();
            }
        }
        assertThat(holds).isNotEmpty();
    }

    @Test
    void backToBackStaysOnTheSameRoomAreAllAccepted() throws Exception {
        Room room = newRoom("101");
        LocalDate base = LocalDate.now().plusDays(40);

        // 8 consecutive 2-night stays: check-out day of one == check-in day of the next. None may be refused.
        List<String> outcomes = runConcurrently(userId -> {
            LocalDate checkIn = base.plusDays(2 * (userId % 8));
            CreateHoldRequest request = new CreateHoldRequest(room.getId(), checkIn, checkIn.plusDays(2), 2);
            return () -> attempt(request, userId);
        }, 8);

        assertThat(outcomes).containsOnly("SUCCESS");
        assertThat(holdRepository.count()).isEqualTo(8);
    }

    @Test
    void differentRoomsAreNotBlockedByEachOther() throws Exception {
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rooms.add(newRoom("5" + i));
        }
        LocalDate checkIn = LocalDate.now().plusDays(10);

        List<String> outcomes = runConcurrently(userId -> {
            Room room = rooms.get((int) (userId % 8));
            return () -> attempt(new CreateHoldRequest(room.getId(), checkIn, checkIn.plusDays(3), 2), userId);
        }, 8);

        assertThat(outcomes).containsOnly("SUCCESS");
    }

    // ------------------------------------------------------------------ helpers

    private Room newRoom(String number) {
        Hotel hotel = hotelRepository.findAll().stream().findFirst().orElseGet(() ->
                hotelRepository.save(new Hotel("Concurrency Hotel", "Chennai", "Tamil Nadu", "India", "Addr", null, 5)));
        return roomRepository.save(new Room(hotel, number, RoomType.DELUXE, new BigDecimal("6000"), 2, null));
    }

    private String attempt(CreateHoldRequest request, long userId) {
        try {
            inventoryService.createHold(request, new AuthenticatedUser(userId, false));
            return "SUCCESS";
        } catch (RoomUnavailableException | PessimisticLockingFailureException rejected) {
            return "REJECTED";
        }
    }

    private List<String> runConcurrently(java.util.function.LongFunction<Callable<String>> attemptForUser) throws Exception {
        return runConcurrently(attemptForUser, CONCURRENT_USERS);
    }

    private List<String> runConcurrently(java.util.function.LongFunction<Callable<String>> attemptForUser, int users)
            throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Callable<String>> attempts = new ArrayList<>();
        for (long userId = 1; userId <= users; userId++) {
            Callable<String> work = attemptForUser.apply(userId);
            attempts.add(() -> {
                startSignal.await();
                return work.call();
            });
        }
        List<String> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(users)) {
            List<Future<String>> futures = attempts.stream().map(pool::submit).toList();
            startSignal.countDown(); // release all threads at once
            for (Future<String> future : futures) {
                outcomes.add(future.get());
            }
        }
        return outcomes;
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
