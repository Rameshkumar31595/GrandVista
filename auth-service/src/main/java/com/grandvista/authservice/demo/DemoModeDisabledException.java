package com.grandvista.authservice.demo;

/** Demo login was requested while DEMO_MODE_ENABLED is not true (HTTP 403). */
public class DemoModeDisabledException extends RuntimeException {

    public DemoModeDisabledException() {
        super("Demo login is disabled on this server");
    }
}
