package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.Role;

/** One card on the Demo Login tab. */
public record DemoAccountResponse(String id, String displayName, Role role, String description) {
}
