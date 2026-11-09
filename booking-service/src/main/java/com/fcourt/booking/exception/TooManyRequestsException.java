package com.fcourt.booking.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 429 (RFC 7807 qua ProblemDetails của Spring) kèm header Retry-After. */
public class TooManyRequestsException extends ResponseStatusException {
    private final long retryAfterSeconds;

    public TooManyRequestsException(String reason, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, reason);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    @Override
    public HttpHeaders getHeaders() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
