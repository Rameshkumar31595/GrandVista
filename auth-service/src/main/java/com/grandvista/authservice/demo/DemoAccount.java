package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.Role;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed allowlist of demonstration identities. Nothing outside this enum can ever be signed in
 * through the demo endpoint, and the roles are fixed here rather than taken from configuration or the request.
 */
public enum DemoAccount {

    RAMESH("ramesh", "demo.ramesh@demo.grandvista.local", Role.ROLE_USER,
            "Search rooms, create bookings and test payments."),
    GUEST2("guest2", "demo.guest2@demo.grandvista.local", Role.ROLE_USER,
            "A second guest: verify that bookings and payments stay private to each user."),
    MANAGER("manager", "demo.manager@demo.grandvista.local", Role.ROLE_ADMIN,
            "Manage hotels, rooms, pricing, bookings and users.");

    /** Reserved e-mail domain: public registration is refused for it, so it can never belong to a real user. */
    public static final String DOMAIN = "demo.grandvista.local";

    private final String id;
    private final String email;
    private final Role role;
    private final String description;

    DemoAccount(String id, String email, Role role, String description) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return role;
    }

    public String description() {
        return description;
    }

    public static Optional<DemoAccount> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(account -> account.id.equals(id)).findFirst();
    }

    public static boolean isReservedEmail(String email) {
        return email != null && email.trim().toLowerCase().endsWith("@" + DOMAIN);
    }
}
