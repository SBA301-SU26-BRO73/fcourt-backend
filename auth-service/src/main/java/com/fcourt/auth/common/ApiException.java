package com.fcourt.auth.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business rule failure carrying a user-safe detail message. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String detail) {
        this(status, detail, Map.of());
    }

    /** Extra top-level fields of the problem body, e.g. reason. */
    public ApiException(HttpStatus status, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.properties = properties;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }
}
