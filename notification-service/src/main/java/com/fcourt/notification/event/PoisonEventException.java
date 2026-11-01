package com.fcourt.notification.event;

/** The record can never be processed (bad JSON, missing ids, malformed data): retrying will not help. */
public class PoisonEventException extends RuntimeException {
    public PoisonEventException(String message) {
        super(message);
    }
}
