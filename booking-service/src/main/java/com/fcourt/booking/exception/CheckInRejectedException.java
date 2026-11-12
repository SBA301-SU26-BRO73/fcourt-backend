package com.fcourt.booking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CheckInRejectedException extends ResponseStatusException {
    private final String reason;

    public CheckInRejectedException(String reason) {
        super(HttpStatus.CONFLICT, "Check-in rejected");
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
