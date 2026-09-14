package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.PricingRule;
import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.RoomType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PricingRuleResponse(
        Long id,
        String name,
        PricingRuleType ruleType,
        BigDecimal multiplier,
        LocalDate startDate,
        LocalDate endDate,
        Integer minOccupancyPercent,
        Long hotelId,
        RoomType roomType,
        boolean active
) {

    public static PricingRuleResponse from(PricingRule rule) {
        return new PricingRuleResponse(rule.getId(), rule.getName(), rule.getRuleType(), rule.getMultiplier(),
                rule.getStartDate(), rule.getEndDate(), rule.getMinOccupancyPercent(), rule.getHotelId(),
                rule.getRoomType(), rule.isActive());
    }
}
