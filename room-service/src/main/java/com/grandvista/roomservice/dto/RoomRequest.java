package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.RoomType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RoomRequest(

        @NotNull(message = "hotelId is required")
        Long hotelId,

        @NotBlank(message = "Room number is required")
        @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "Room number may contain letters, digits and '-' (max 20)")
        String roomNumber,

        @NotNull(message = "Room type is required (STANDARD, DELUXE or SUITE)")
        RoomType roomType,

        @NotNull(message = "Base price is required")
        @DecimalMin(value = "1.00", message = "Base price must be positive")
        @DecimalMax(value = "1000000.00", message = "Base price is unrealistically high")
        @Digits(integer = 8, fraction = 2, message = "Base price may have at most 2 decimal places")
        BigDecimal basePrice,

        @NotNull(message = "Capacity is required")
        @Min(value = 1, message = "Capacity must be at least 1 guest")
        @Max(value = 10, message = "Capacity cannot exceed 10 guests")
        Integer capacity,

        @Size(max = 1000)
        String description
) {
}
