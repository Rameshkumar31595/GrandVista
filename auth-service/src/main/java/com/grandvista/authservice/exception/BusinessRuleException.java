package com.grandvista.authservice.exception;

/**
 * A request that is well-formed but violates a business rule (HTTP 409).
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
