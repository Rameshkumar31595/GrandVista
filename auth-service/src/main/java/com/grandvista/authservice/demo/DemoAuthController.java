package com.grandvista.authservice.demo;

import com.grandvista.authservice.dto.AuthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Review demo login. Both endpoints answer 403 unless DEMO_MODE_ENABLED=true. */
@RestController
@RequestMapping("/auth/demo")
@Tag(name = "Demo login")
public class DemoAuthController {

    private final DemoLoginService demoLoginService;

    public DemoAuthController(DemoLoginService demoLoginService) {
        this.demoLoginService = demoLoginService;
    }

    @GetMapping("/accounts")
    @Operation(summary = "List the demo accounts (403 when demo mode is disabled)")
    public List<DemoAccountResponse> accounts() {
        return demoLoginService.listAccounts();
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in as one of the fixed demo accounts (403 when demo mode is disabled)")
    public AuthResponse login(@Valid @RequestBody DemoLoginRequest request) {
        return demoLoginService.login(request.accountId());
    }
}
