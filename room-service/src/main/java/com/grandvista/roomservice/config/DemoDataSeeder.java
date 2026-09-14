package com.grandvista.roomservice.config;

import com.grandvista.roomservice.entity.Hotel;
import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.Room;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.repository.HotelRepository;
import com.grandvista.roomservice.repository.PricingRuleRepository;
import com.grandvista.roomservice.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;

/**
 * Loads a small set of FICTIONAL demonstration data (4 hotels, 16 rooms, 4 pricing rules)
 * the first time the service starts against an empty database. Disable with SEED_DEMO_DATA=false.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final InventoryProperties properties;
    private final Clock clock;

    public DemoDataSeeder(HotelRepository hotelRepository, RoomRepository roomRepository,
                          PricingRuleRepository pricingRuleRepository, InventoryProperties properties, Clock clock) {
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.pricingRuleRepository = pricingRuleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.seedDemoData() || hotelRepository.count() > 0) {
            return;
        }
        Hotel hyderabad = hotel("GrandVista Banjara Residency", "Hyderabad", "Telangana",
                "Road No. 12, Banjara Hills, Hyderabad 500034", 5);
        Hotel vijayawada = hotel("GrandVista Krishna Riverside", "Vijayawada", "Andhra Pradesh",
                "MG Road, Labbipet, Vijayawada 520010", 4);
        Hotel bengaluru = hotel("GrandVista Indiranagar Suites", "Bengaluru", "Karnataka",
                "100 Feet Road, Indiranagar, Bengaluru 560038", 4);
        Hotel chennai = hotel("GrandVista Marina Bay", "Chennai", "Tamil Nadu",
                "East Coast Road, Besant Nagar, Chennai 600090", 5);

        rooms(hyderabad, "4200", "6500", "11000");
        rooms(vijayawada, "2800", "4300", "7500");
        rooms(bengaluru, "3900", "5800", "9800");
        rooms(chennai, "4000", "6200", "12500");

        int year = LocalDate.now(clock).getYear();
        LocalDate festiveEnd = MonthDay.of(11, 15).atYear(year);
        int festiveYear = LocalDate.now(clock).isAfter(festiveEnd) ? year + 1 : year;
        pricingRuleRepository.save(new PricingRule("Dasara & Diwali Festive Season", PricingRuleType.SEASONAL,
                new BigDecimal("1.25"), LocalDate.of(festiveYear, 10, 15), LocalDate.of(festiveYear, 11, 15),
                null, null, null, true));
        pricingRuleRepository.save(new PricingRule("Year-End Holidays", PricingRuleType.SEASONAL,
                new BigDecimal("1.40"), LocalDate.of(festiveYear, 12, 20), LocalDate.of(festiveYear + 1, 1, 5),
                null, null, null, true));
        pricingRuleRepository.save(new PricingRule("High Demand (50%+ occupancy)", PricingRuleType.OCCUPANCY,
                new BigDecimal("1.10"), null, null, 50, null, null, true));
        pricingRuleRepository.save(new PricingRule("Peak Demand (75%+ occupancy)", PricingRuleType.OCCUPANCY,
                new BigDecimal("1.25"), null, null, 75, null, null, true));

        log.info("Demo data loaded: {} hotels, {} rooms, {} pricing rules",
                hotelRepository.count(), roomRepository.count(), pricingRuleRepository.count());
    }

    private Hotel hotel(String name, String city, String state, String address, int stars) {
        return hotelRepository.save(new Hotel(name, city, state, "India", address,
                "Fictional GrandVista property used for demonstration.", stars));
    }

    private void rooms(Hotel hotel, String standardPrice, String deluxePrice, String suitePrice) {
        roomRepository.save(new Room(hotel, "101", RoomType.STANDARD, new BigDecimal(standardPrice), 2,
                "Queen bed, city view, work desk"));
        roomRepository.save(new Room(hotel, "102", RoomType.STANDARD, new BigDecimal(standardPrice), 2,
                "Twin beds, garden view"));
        roomRepository.save(new Room(hotel, "203", RoomType.DELUXE, new BigDecimal(deluxePrice), 3,
                "King bed, lounge chair, premium amenities"));
        roomRepository.save(new Room(hotel, "301", RoomType.SUITE, new BigDecimal(suitePrice), 4,
                "Separate living room, king bed, panoramic view"));
    }
}
