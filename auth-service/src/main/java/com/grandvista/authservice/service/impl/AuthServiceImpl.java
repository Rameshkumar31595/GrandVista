package com.grandvista.authservice.service.impl;

import com.grandvista.authservice.demo.DemoAccount;
import com.grandvista.authservice.dto.AuthResponse;
import com.grandvista.authservice.dto.LoginRequest;
import com.grandvista.authservice.dto.RegisterRequest;
import com.grandvista.authservice.dto.UserResponse;
import com.grandvista.authservice.entity.Role;
import com.grandvista.authservice.entity.User;
import com.grandvista.authservice.exception.BusinessRuleException;
import com.grandvista.authservice.exception.DuplicateEmailException;
import com.grandvista.authservice.exception.InvalidCredentialsException;
import com.grandvista.authservice.exception.ResourceNotFoundException;
import com.grandvista.authservice.repository.UserRepository;
import com.grandvista.authservice.security.JwtTokenProvider;
import com.grandvista.authservice.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (DemoAccount.isReservedEmail(email)) {
            throw new BusinessRuleException("This e-mail domain is reserved");
        }
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        // Role is fixed server-side: public registration can never create an administrator.
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()), Role.ROLE_USER);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent registrations with the same email: the unique constraint wins.
            throw new DuplicateEmailException(email);
        }
        log.info("Registered new user id={} email={}", user.getId(), user.getEmail());
        return UserResponse.from(user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException ex) {
            log.info("Failed login attempt for email={}", email);
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
        JwtTokenProvider.IssuedToken token = tokenProvider.issueToken(user);
        log.info("User id={} logged in with role {}", user.getId(), user.getRole());
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), UserResponse.from(user));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User account no longer exists"));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
