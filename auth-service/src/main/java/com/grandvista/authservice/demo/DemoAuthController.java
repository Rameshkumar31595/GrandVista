package com.grandvista.authservice.demo;

import com.grandvista.authservice.dto.AuthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Review demo login. Both endpoints answer 403 unless DEMO_MODE_ENABLED=true. When a reviewer access code is
 * configured (public deployments) they also need the {@value DemoAccessGuard#HEADER} header: 401 without it,
 * 429 after repeated wrong codes.
 */
@RestController
@RequestMapping("/auth/demo")
@Tag(name = "Demo login")
public class DemoAuthController {

    private final DemoLoginService demoLoginService;
    private final DemoAccessGuard accessGuard;

    public DemoAuthController(DemoLoginService demoLoginService, DemoAccessGuard accessGuard) {
        this.demoLoginService = demoLoginService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/accounts")
    @Operation(summary = "List the demo accounts (403 when demo mode is disabled, 401 without the reviewer access code)")
    public List<DemoAccountResponse> accounts(
            @RequestHeader(value = DemoAccessGuard.HEADER, required = false) String accessCode,
            HttpServletRequest request) {
        accessGuard.verify(accessCode, clientAddress(request));
        return demoLoginService.listAccounts();
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in as one of the fixed demo accounts (403 when demo mode is disabled, 401 without the reviewer access code)")
    public AuthResponse login(@RequestHeader(value = DemoAccessGuard.HEADER, required = false) String accessCode,
                              @Valid @RequestBody DemoLoginRequest request, HttpServletRequest httpRequest) {
        accessGuard.verify(accessCode, clientAddress(httpRequest));
        return demoLoginService.login(request.accountId());
    }

    /**
     * Best-effort client address for rate limiting: the visitor address set by the gateway (X-GrandVista-Client, cloud),
     * else the first X-Forwarded-For entry, else the socket address. A client calling this service directly can forge
     * these headers, which is why the guard also has a global limit.
     */
    private static String clientAddress(HttpServletRequest request) {
        String fromGateway = request.getHeader("X-GrandVista-Client");
        if (fromGateway != null && !fromGateway.isBlank() && fromGateway.length() <= 64) {
            return fromGateway.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
