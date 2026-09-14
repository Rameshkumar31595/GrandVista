package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.util.StayPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCalculatorTest {

    private static final Long HOTEL_ID = 1L;
    private static final BigDecimal BASE = new BigDecimal("4000.00");

    private final PriceCalculator calculator = new PriceCalculator();

    @Test
    void withoutRulesFinalPriceEqualsBasePriceTimesNights() {
        PriceQuoteResponse quote = calculate(stay("2026-09-20", "2026-09-23"), List.of(), 0);

        assertThat(quote.nights()).isEqualTo(3);
        assertThat(quote.baseTotal()).isEqualByComparingTo("12000.00");
        assertThat(quote.finalTotal()).isEqualByComparingTo("12000.00");
        assertThat(quote.pricingAdjustment()).isEqualByComparingTo("0");
        assertThat(quote.appliedRules()).isEmpty();
    }

    @Test
    void seasonalMultiplierAppliesOnlyToNightsInsideTheSeason() {
        // Season starts on 2026-10-15; stay has nights 14th (normal), 15th and 16th (festive).
        PricingRule festive = seasonal("Festive", "1.25", "2026-10-15", "2026-11-15");

        PriceQuoteResponse quote = calculate(stay("2026-10-14", "2026-10-17"), List.of(festive), 0);

        assertThat(quote.nightlyBreakdown()).extracting(PriceQuoteResponse.NightlyPrice::price)
                .containsExactly(new BigDecimal("4000.00"), new BigDecimal("5000.00"), new BigDecimal("5000.00"));
        assertThat(quote.finalTotal()).isEqualByComparingTo("14000.00");
        assertThat(quote.pricingAdjustment()).isEqualByComparingTo("2000.00");
        assertThat(quote.appliedRules()).containsExactly("Festive");
    }

    @Test
    void occupancyRuleAppliesOnlyAboveThresholdAndHighestMatchingWins() {
        PricingRule high = occupancy("High demand", "1.10", 50);
        PricingRule peak = occupancy("Peak demand", "1.25", 75);
        StayPeriod twoNights = stay("2026-09-20", "2026-09-22");

        assertThat(calculate(twoNights, List.of(high, peak), 49).finalTotal()).isEqualByComparingTo("8000.00");
        assertThat(calculate(twoNights, List.of(high, peak), 50).finalTotal()).isEqualByComparingTo("8800.00");

        PriceQuoteResponse peakQuote = calculate(twoNights, List.of(high, peak), 80);
        assertThat(peakQuote.demandMultiplier()).isEqualByComparingTo("1.25");
        assertThat(peakQuote.finalTotal()).isEqualByComparingTo("10000.00");
        assertThat(peakQuote.appliedRules()).containsExactly("Peak demand");
    }

    @Test
    void seasonalAndDemandMultipliersCombine() {
        PricingRule festive = seasonal("Festive", "1.25", "2026-10-15", "2026-11-15");
        PricingRule high = occupancy("High demand", "1.10", 50);

        PriceQuoteResponse quote = calculate(stay("2026-10-20", "2026-10-21"), List.of(festive, high), 60);

        // 4000 x 1.25 x 1.10 = 5500
        assertThat(quote.finalTotal()).isEqualByComparingTo("5500.00");
        assertThat(quote.appliedRules()).containsExactlyInAnyOrder("Festive", "High demand");
    }

    @Test
    void rulesScopedToAnotherHotelOrRoomTypeAreIgnored() {
        PricingRule otherHotel = new PricingRule("Other hotel", PricingRuleType.OCCUPANCY, new BigDecimal("2.00"),
                null, null, 1, 99L, null, true);
        PricingRule suitesOnly = new PricingRule("Suites only", PricingRuleType.OCCUPANCY, new BigDecimal("2.00"),
                null, null, 1, null, RoomType.SUITE, true);
        PricingRule inactive = new PricingRule("Disabled", PricingRuleType.OCCUPANCY, new BigDecimal("2.00"),
                null, null, 1, null, null, false);

        PriceQuoteResponse quote = calculate(stay("2026-09-20", "2026-09-21"),
                List.of(otherHotel, suitesOnly, inactive), 100);

        assertThat(quote.finalTotal()).isEqualByComparingTo("4000.00");
    }

    private PriceQuoteResponse calculate(StayPeriod stay, List<PricingRule> rules, int occupancy) {
        return calculator.calculate(10L, HOTEL_ID, RoomType.STANDARD, BASE, stay, rules, occupancy);
    }

    private static StayPeriod stay(String in, String out) {
        return new StayPeriod(LocalDate.parse(in), LocalDate.parse(out));
    }

    private static PricingRule seasonal(String name, String multiplier, String start, String end) {
        return new PricingRule(name, PricingRuleType.SEASONAL, new BigDecimal(multiplier), LocalDate.parse(start),
                LocalDate.parse(end), null, null, null, true);
    }

    private static PricingRule occupancy(String name, String multiplier, int threshold) {
        return new PricingRule(name, PricingRuleType.OCCUPANCY, new BigDecimal(multiplier), null, null,
                threshold, null, null, true);
    }
}
