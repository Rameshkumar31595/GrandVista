package com.grandvista.bookingservice.exception;

/**
 * Well-formed request that conflicts with current state, e.g. duplicate room number (HTTP 409).
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
