package com.grandvista.authservice.config;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Safe administrator bootstrap: public registration can only create ROLE_USER accounts,
 * so the first administrator is created here at startup from configuration.
 * <p>
 * Runs only when no ROLE_ADMIN exists yet and ADMIN_PASSWORD is configured; the password
 * is never logged and is never present in source code.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;

    public AdminInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminProperties = adminProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ROLE_ADMIN)) {
            log.info("Administrator account already present - skipping admin bootstrap");
            return;
        }
        String password = adminProperties.password();
        if (password == null || password.isBlank()) {
            log.warn("No administrator exists and ADMIN_PASSWORD is not configured - admin bootstrap skipped");
            return;
        }
        if (password.length() < 8) {
            log.warn("ADMIN_PASSWORD must be at least 8 characters - admin bootstrap skipped");
            return;
        }
        String email = adminProperties.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            log.warn("Email {} is already used by a non-admin account - admin bootstrap skipped", email);
            return;
        }
        userRepository.save(new User(adminProperties.name(), email, passwordEncoder.encode(password), Role.ROLE_ADMIN));
        log.info("Demonstration administrator created with email {}", email);
    }
}
