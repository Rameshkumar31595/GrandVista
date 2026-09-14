package com.grandvista.authservice.repository;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA slice test on an in-memory H2 database (no PostgreSQL needed).
 */
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void emailMustBeUniqueAtDatabaseLevel() {
        userRepository.saveAndFlush(new User("First", "same@example.com", "hash", Role.ROLE_USER));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(new User("Second", "same@example.com", "hash", Role.ROLE_USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void timestampsAndRoleLookupWork() {
        User saved = userRepository.saveAndFlush(new User("Admin", "admin@example.com", "hash", Role.ROLE_ADMIN));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(userRepository.existsByRole(Role.ROLE_ADMIN)).isTrue();
        assertThat(userRepository.findByEmail("admin@example.com")).isPresent();
    }
}
