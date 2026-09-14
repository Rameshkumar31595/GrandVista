package com.grandvista.roomservice.service;

import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.dto.PriceQuoteResponse.NightlyPrice;
import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.util.StayPeriod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rule-based dynamic pricing. Pure calculation - no database access - so it can be unit tested
 * and explained line by line.
 *
 * <pre>
 * for every night of the stay:
 *     seasonalMultiplier = highest active SEASONAL rule covering that night   (else 1.00)
 *     demandMultiplier   = highest active OCCUPANCY rule whose threshold <= hotel occupancy (else 1.00)
 *     nightlyPrice       = basePrice x seasonalMultiplier x demandMultiplier
 * finalTotal = sum of nightly prices
 * </pre>
 * When several rules of the same type match, the highest multiplier wins; multipliers of the
 * same type are never compounded, which keeps prices predictable.
 */
@Component
public class PriceCalculator {

    public static final String CURRENCY = "INR";
    private static final BigDecimal NO_ADJUSTMENT = new BigDecimal("1.00");

    public PriceQuoteResponse calculate(Long roomId, Long hotelId, RoomType roomType, BigDecimal basePrice,
                                        StayPeriod stay, List<PricingRule> activeRules, int occupancyPercent) {
        List<PricingRule> applicable = activeRules.stream()
                .filter(rule -> rule.appliesTo(hotelId, roomType))
                .toList();
        Set<String> appliedRuleNames = new LinkedHashSet<>();

        Optional<PricingRule> demandRule = applicable.stream()
                .filter(rule -> rule.getRuleType() == PricingRuleType.OCCUPANCY)
                .filter(rule -> rule.getMinOccupancyPercent() != null && occupancyPercent >= rule.getMinOccupancyPercent())
                .max(Comparator.comparing(PricingRule::getMultiplier));
        BigDecimal demandMultiplier = demandRule.map(PricingRule::getMultiplier).orElse(NO_ADJUSTMENT);
        demandRule.ifPresent(rule -> appliedRuleNames.add(rule.getName()));

        List<NightlyPrice> nights = new ArrayList<>();
        BigDecimal finalTotal = BigDecimal.ZERO;
        for (LocalDate night : stay.nightDates()) {
            Optional<PricingRule> seasonalRule = applicable.stream()
                    .filter(rule -> rule.getRuleType() == PricingRuleType.SEASONAL)
                    .filter(rule -> covers(rule, night))
                    .max(Comparator.comparing(PricingRule::getMultiplier));
            BigDecimal seasonalMultiplier = seasonalRule.map(PricingRule::getMultiplier).orElse(NO_ADJUSTMENT);
            seasonalRule.ifPresent(rule -> appliedRuleNames.add(rule.getName()));

            BigDecimal nightlyPrice = basePrice.multiply(seasonalMultiplier).multiply(demandMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);
            nights.add(new NightlyPrice(night, seasonalMultiplier, demandMultiplier, nightlyPrice));
            finalTotal = finalTotal.add(nightlyPrice);
        }

        BigDecimal baseTotal = basePrice.multiply(BigDecimal.valueOf(stay.nights())).setScale(2, RoundingMode.HALF_UP);
        return new PriceQuoteResponse(roomId, stay.checkIn(), stay.checkOut(), stay.nights(), CURRENCY,
                basePrice.setScale(2, RoundingMode.HALF_UP), baseTotal, occupancyPercent, demandMultiplier,
                List.copyOf(appliedRuleNames), nights, finalTotal.subtract(baseTotal), finalTotal);
    }

    private static boolean covers(PricingRule rule, LocalDate night) {
        return rule.getStartDate() != null && rule.getEndDate() != null
                && !night.isBefore(rule.getStartDate()) && !night.isAfter(rule.getEndDate());
    }
}
