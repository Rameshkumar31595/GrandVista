package com.grandvista.authservice.dto;

import com.grandvista.authservice.entity.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(

        @NotNull(message = "Role is required (ROLE_USER or ROLE_ADMIN)")
        Role role
) {
}
