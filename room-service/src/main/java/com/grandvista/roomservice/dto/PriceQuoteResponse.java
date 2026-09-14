package com.grandvista.roomservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Transparent price calculation:
 * <pre>nightlyPrice = basePrice x seasonalMultiplier(night) x demandMultiplier</pre>
 */
public record PriceQuoteResponse(
        Long roomId,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        String currency,
        BigDecimal basePricePerNight,
        BigDecimal baseTotal,
        int occupancyPercent,
        BigDecimal demandMultiplier,
        List<String> appliedRules,
        List<NightlyPrice> nightlyBreakdown,
        BigDecimal pricingAdjustment,
        BigDecimal finalTotal
) {

    public record NightlyPrice(LocalDate date, BigDecimal seasonalMultiplier, BigDecimal demandMultiplier,
                               BigDecimal price) {
    }
}
