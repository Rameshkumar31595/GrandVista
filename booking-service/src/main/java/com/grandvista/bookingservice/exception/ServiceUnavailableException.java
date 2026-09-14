package com.grandvista.bookingservice.exception;

/**
 * A downstream service is not registered in Eureka, is unreachable, timed out or returned 5xx (HTTP 503).
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String serviceName, Throwable cause) {
        super(serviceName + " is currently unavailable. Please try again shortly.", cause);
    }
}
