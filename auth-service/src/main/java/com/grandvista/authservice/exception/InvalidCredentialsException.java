package com.grandvista.authservice.exception;

/**
 * Thrown for a failed login. The message is intentionally identical for
 * "unknown email" and "wrong password" so attackers cannot enumerate accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
