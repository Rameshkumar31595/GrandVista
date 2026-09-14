package com.grandvista.roomservice.dto;

import com.grandvista.roomservice.entity.RoomStatus;
import jakarta.validation.constraints.NotNull;

public record RoomStatusUpdateRequest(

        @NotNull(message = "Status is required (ACTIVE, MAINTENANCE or INACTIVE)")
        RoomStatus status
) {
}
