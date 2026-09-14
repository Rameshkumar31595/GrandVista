package com.grandvista.bookingservice.exception;

/**
 * A downstream service (Room / Payment) rejected the request with a 4xx business error,
 * e.g. 409 "room already reserved". The original status and message are passed on to the client.
 */
public class DownstreamServiceException extends RuntimeException {

    private final int status;
    private final String serviceName;

    public DownstreamServiceException(String serviceName, int status, String message) {
        super(message);
        this.serviceName = serviceName;
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public String getServiceName() {
        return serviceName;
    }
}
