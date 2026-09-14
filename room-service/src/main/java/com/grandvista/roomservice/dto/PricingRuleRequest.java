package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.PricingRuleType;
import com.grandvista.roomservice.entity.RoomType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * SEASONAL rules need startDate/endDate; OCCUPANCY rules need minOccupancyPercent.
 * Those cross-field checks are done in the service layer.
 */
public record PricingRuleRequest(

        @NotBlank(message = "Rule name is required")
        @Size(max = 100)
        String name,

        @NotNull(message = "Rule type is required (SEASONAL or OCCUPANCY)")
        PricingRuleType ruleType,

        @NotNull(message = "Multiplier is required")
        @DecimalMin(value = "0.50", message = "Multiplier must be at least 0.50")
        @DecimalMax(value = "3.00", message = "Multiplier must be at most 3.00")
        @Digits(integer = 1, fraction = 2, message = "Multiplier may have at most 2 decimal places")
        BigDecimal multiplier,

        LocalDate startDate,

        LocalDate endDate,

        @Min(value = 1, message = "Occupancy threshold must be between 1 and 100")
        @Max(value = 100, message = "Occupancy threshold must be between 1 and 100")
        Integer minOccupancyPercent,

        Long hotelId,

        RoomType roomType,

        Boolean active
) {
}
