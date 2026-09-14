package com.grandvista.authservice.service.impl;

import com.grandvista.authservice.dto.AuthResponse;
import com.grandvista.authservice.dto.LoginRequest;
import com.grandvista.authservice.dto.RegisterRequest;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.DuplicateEmailException;
import com.grandvista.authservice.exception.InvalidCredentialsException;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtTokenProvider tokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void registerHashesPasswordNormalizesEmailAndAssignsUserRole() {
        when(userRepository.existsByEmail("asha@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Secret123")).thenReturn("$2a$10$hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = authService.register(new RegisterRequest("Asha", "  Asha@Example.com ", "Secret123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo("$2a$10$hashed").isNotEqualTo("Secret123");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(response.email()).isEqualTo("asha@example.com");
        assertThat(response.role()).isEqualTo(Role.ROLE_USER);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("asha@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Asha", "asha@example.com", "Secret123")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void loginWithWrongPasswordThrowsInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(tokenProvider, never()).issueToken(any());
    }

    @Test
    void successfulLoginReturnsBearerToken() {
        User user = new User("Asha", "asha@example.com", "hash", Role.ROLE_USER);
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));
        Instant expiry = Instant.parse("2026-09-14T10:00:00Z");
        when(tokenProvider.issueToken(user)).thenReturn(new JwtTokenProvider.IssuedToken("jwt-value", expiry));

        AuthResponse response = authService.login(new LoginRequest("asha@example.com", "Secret123"));

        assertThat(response.accessToken()).isEqualTo("jwt-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiry);
    }
}
