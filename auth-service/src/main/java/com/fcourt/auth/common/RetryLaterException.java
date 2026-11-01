package com.fcourt.auth.common;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429 Too Many Requests; the handler adds a {@code Retry-After} header in seconds. */
public class RetryLaterException extends ApiException {
    private final Duration retryAfter;

    public RetryLaterException(String detail, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, detail);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
