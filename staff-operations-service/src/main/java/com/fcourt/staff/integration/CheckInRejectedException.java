package com.fcourt.staff.integration;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class CheckInRejectedException extends ResponseStatusException {
    private final String reason;

    public CheckInRejectedException(HttpStatusCode status, String reason) {
        super(status, "Check-in rejected");
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
