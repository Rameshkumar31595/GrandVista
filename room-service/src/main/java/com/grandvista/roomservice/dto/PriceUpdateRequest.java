package com.grandvista.roomservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PriceUpdateRequest(

        @NotNull(message = "Base price is required")
        @DecimalMin(value = "1.00", message = "Base price must be positive")
        @DecimalMax(value = "1000000.00", message = "Base price is unrealistically high")
        @Digits(integer = 8, fraction = 2, message = "Base price may have at most 2 decimal places")
        BigDecimal basePrice
) {
}
