package com.grandvista.bookingservice.exception;

/**
 * Semantically invalid input that Bean Validation cannot express alone,
 * e.g. a reversed date range (HTTP 400).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
