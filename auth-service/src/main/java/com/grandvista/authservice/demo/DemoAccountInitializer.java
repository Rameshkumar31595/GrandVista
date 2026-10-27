package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the three demo accounts (only when demo mode is enabled) if they do not exist yet.
 * <p>
 * Each account gets a random BCrypt password that is generated here and thrown away, so the
 * accounts cannot be used through the normal password login - only through the demo endpoint.
 * Existing rows are never replaced; only the cosmetic display name of a demo account is kept in sync.
 * Safe to run on every start (idempotent).
 */
@Component
public class DemoAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountInitializer.class);

    private final DemoProperties properties;
    private final DemoLoginService demoLoginService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public DemoAccountInitializer(DemoProperties properties, DemoLoginService demoLoginService,
                                  UserRepository userRepository, PasswordEncoder passwordEncoder,
                                  Environment environment) {
        this.properties = properties;
        this.demoLoginService = demoLoginService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException(
                    "DEMO_MODE_ENABLED=true is not allowed with the prod/production profile - refusing to start");
        }
        log.warn("DEMO MODE IS ENABLED: passwordless demo login is available. Use only for local/academic demonstrations.");
        for (DemoAccount account : DemoAccount.values()) {
            String name = demoLoginService.displayName(account);
            userRepository.findByEmail(account.email()).ifPresentOrElse(existing -> {
                if (existing.getRole() != account.role()) {
                    // Collision with a row we did not create: leave it completely alone (demo login refuses it too).
                    log.warn("Demo account {} collides with an existing {} user - left untouched, demo login for it stays unavailable",
                            account.id(), existing.getRole());
                } else if (!existing.getName().equals(name)) {
                    existing.setName(name);
                    userRepository.save(existing);
                }
            }, () -> {
                userRepository.save(new User(name, account.email(), passwordEncoder.encode(randomSecret()),
                        account.role()));
                log.info("Created demo account {} ({})", account.id(), account.role());
            });
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[36];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
