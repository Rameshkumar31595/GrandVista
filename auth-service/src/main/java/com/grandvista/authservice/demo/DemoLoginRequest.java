package com.grandvista.authservice.demo;

import jakarta.validation.constraints.NotBlank;

public record DemoLoginRequest(@NotBlank(message = "Demo account is required") String accountId) {
}
