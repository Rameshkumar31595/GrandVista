package com.grandvista.roomservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HotelRequest(

        @NotBlank(message = "Hotel name is required")
        @Size(max = 120, message = "Hotel name must be at most 120 characters")
        String name,

        @NotBlank(message = "City is required")
        @Size(max = 80)
        String city,

        @NotBlank(message = "State is required")
        @Size(max = 80)
        String state,

        @NotBlank(message = "Country is required")
        @Size(max = 80)
        String country,

        @NotBlank(message = "Address is required")
        @Size(max = 255)
        String address,

        @Size(max = 1000)
        String description,

        @NotNull(message = "Star rating is required")
        @Min(value = 1, message = "Star rating must be between 1 and 5")
        @Max(value = 5, message = "Star rating must be between 1 and 5")
        Integer starRating
) {
}
