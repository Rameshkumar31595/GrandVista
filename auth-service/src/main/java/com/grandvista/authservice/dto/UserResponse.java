package com.grandvista.authservice.dto;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;

import java.time.Instant;

/**
 * Public view of a user account - never contains the password hash.
 */
public record UserResponse(
        Long id,
        String name,
        String email,
        Role role,
        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
