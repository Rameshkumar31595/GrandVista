package com.grandvista.authservice.demo;

/** The reviewer access code is missing or wrong (HTTP 401). */
public class DemoAccessDeniedException extends RuntimeException {

    public DemoAccessDeniedException(String message) {
        super(message);
    }
}
