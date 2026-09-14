package com.grandvista.authservice.service;

import com.grandvista.authservice.dto.AuthResponse;
import com.grandvista.authservice.dto.LoginRequest;
import com.grandvista.authservice.dto.RegisterRequest;
import com.grandvista.authservice.dto.UserResponse;

public interface AuthService {

    /** Creates a new ROLE_USER account. */
    UserResponse register(RegisterRequest request);

    /** Verifies credentials and issues a signed JWT. */
    AuthResponse login(LoginRequest request);

    UserResponse getCurrentUser(Long userId);
}
