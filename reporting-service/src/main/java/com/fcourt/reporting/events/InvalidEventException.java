package com.fcourt.reporting.events;

/** A record that can never be applied; it is sent to {@code <topic>.DLT} without retries. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }
}
