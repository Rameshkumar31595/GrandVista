package com.grandvista.authservice.demo;

import com.grandvista.authservice.dto.AuthResponse;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.InvalidCredentialsException;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Passwordless sign-in for the three fixed demo accounts. Issues the same JWT as a normal login
 * (same issuer, expiry and roles claim), so every downstream authorization rule is unchanged.
 */
@Service
public class DemoLoginService {

    private static final Logger log = LoggerFactory.getLogger(DemoLoginService.class);

    private final DemoProperties properties;
    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;

    public DemoLoginService(DemoProperties properties, UserRepository userRepository, JwtTokenProvider tokenProvider) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
    }

    public List<DemoAccountResponse> listAccounts() {
        requireEnabled();
        return Arrays.stream(DemoAccount.values())
                .map(account -> new DemoAccountResponse(account.id(), displayName(account), account.role(),
                        account.description()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String accountId) {
        requireEnabled();
        DemoAccount account = DemoAccount.fromId(accountId).orElseThrow(() -> {
            log.warn("Demo login rejected: unknown demo account '{}'", sanitize(accountId));
            return new InvalidCredentialsException();
        });
        // The account must exist AND still carry its fixed role (an admin could have re-roled it).
        User user = userRepository.findByEmail(account.email())
                .filter(found -> found.getRole() == account.role())
                .orElseThrow(() -> {
                    log.warn("Demo login rejected: account {} is missing or its role was changed", account.id());
                    return new InvalidCredentialsException();
                });
        JwtTokenProvider.IssuedToken token = tokenProvider.issueToken(user);
        log.info("Demo login: account={} userId={} role={}", account.id(), user.getId(), user.getRole());
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    String displayName(DemoAccount account) {
        String configured = switch (account) {
            case RAMESH -> properties.guestOneName();
            case GUEST2 -> properties.guestTwoName();
            case MANAGER -> properties.managerName();
        };
        return configured == null || configured.isBlank() ? account.id() : configured.trim();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new DemoModeDisabledException();
        }
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9_-]", "?");
    }
}
