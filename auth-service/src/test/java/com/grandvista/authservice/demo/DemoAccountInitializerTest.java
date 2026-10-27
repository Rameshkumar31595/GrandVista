package com.grandvista.authservice.demo;

import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Account-collision and enable/disable behaviour of the demo account bootstrap (no Spring context, no database). */
class DemoAccountInitializerTest {

    private UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DemoAccountInitializer initializer(boolean enabled, MockEnvironment environment) {
        DemoProperties properties = new DemoProperties(enabled, "Ramesh", "Demo Guest 2", "GrandVista Manager");
        DemoLoginService service = new DemoLoginService(properties, users, mock(JwtTokenProvider.class));
        return new DemoAccountInitializer(properties, service, users, encoder, environment);
    }

    @Test
    void disabledModeNeverTouchesTheUserTable() {
        initializer(false, new MockEnvironment()).run(new DefaultApplicationArguments());

        verifyNoInteractions(users);
    }

    @Test
    void enabledModeCreatesOnlyTheThreeMissingDemoAccountsWithUnknownPasswords() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        initializer(true, new MockEnvironment()).run(new DefaultApplicationArguments());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(User::getEmail)
                .containsExactly("demo.ramesh@demo.grandvista.local", "demo.guest2@demo.grandvista.local",
                        "demo.manager@demo.grandvista.local");
        assertThat(saved.getAllValues()).extracting(User::getRole)
                .containsExactly(Role.ROLE_USER, Role.ROLE_USER, Role.ROLE_ADMIN);
        // the random password is not any value a person could type
        assertThat(saved.getAllValues()).noneMatch(user -> encoder.matches("Password123", user.getPassword()));
    }

    @Test
    void anExistingRowWithADifferentRoleIsLeftCompletelyUntouched() {
        User collision = new User("Someone Real", "demo.manager@demo.grandvista.local", "$2a$10$existingHash",
                Role.ROLE_USER);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByEmail("demo.manager@demo.grandvista.local")).thenReturn(Optional.of(collision));

        initializer(true, new MockEnvironment()).run(new DefaultApplicationArguments());

        assertThat(collision.getName()).isEqualTo("Someone Real");
        assertThat(collision.getPassword()).isEqualTo("$2a$10$existingHash");
        assertThat(collision.getRole()).isEqualTo(Role.ROLE_USER);
        verify(users, never()).save(collision);
    }

    @Test
    void anExistingDemoRowKeepsItsPasswordAndIsNeverRecreated() {
        User existing = new User("Old Name", "demo.ramesh@demo.grandvista.local", "$2a$10$keepMe", Role.ROLE_USER);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByEmail("demo.ramesh@demo.grandvista.local")).thenReturn(Optional.of(existing));

        initializer(true, new MockEnvironment()).run(new DefaultApplicationArguments());

        assertThat(existing.getPassword()).isEqualTo("$2a$10$keepMe");
        assertThat(existing.getName()).isEqualTo("Ramesh"); // cosmetic name sync only
        verify(users, org.mockito.Mockito.times(1)).save(existing);
    }

    @Test
    void aRealUserWithTheSameDisplayNameIsNotTouched() {
        User real = new User("Ramesh", "ramesh@example.com", "$2a$10$realHash", Role.ROLE_USER);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByEmail("ramesh@example.com")).thenReturn(Optional.of(real));

        initializer(true, new MockEnvironment()).run(new DefaultApplicationArguments());

        verify(users, never()).save(real);
        verify(users, never()).findByEmail("ramesh@example.com");
        assertThat(real.getPassword()).isEqualTo("$2a$10$realHash");
    }

    @Test
    void enabledDemoModeRefusesToStartWithAProductionProfile() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThatThrownBy(() -> initializer(true, production).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users);
    }
}
