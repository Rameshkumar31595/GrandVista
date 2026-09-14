package com.grandvista.roomservice.exception;

/**
 * The room cannot be reserved for the requested dates (HTTP 409).
 */
public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException(String message) {
        super(message);
    }
}
