package com.fcourt.notification.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central error handling: every API error is an RFC 7807 {@link ProblemDetail}. Status codes are the ones each
 * exception already produced ({@code ResponseStatusException} keeps its status and reason as {@code detail});
 * only the body shape is unified. {@code detail} is always user-safe, internal messages are logged, not returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
        List<String> errors = error.getBindingResult().getAllErrors().stream().map(item ->
                item instanceof org.springframework.validation.FieldError field
                        ? field.getField() + ": " + field.getDefaultMessage()
                        : item.getDefaultMessage()).sorted().toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(error, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        return handleExceptionInternal(error, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Last resort: a generic 500. Security and {@code @ResponseStatus} exceptions keep their own handling. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception error, WebRequest request) throws Exception {
        if (error instanceof AccessDeniedException || error instanceof AuthenticationException
                || AnnotatedElementUtils.findMergedAnnotation(error.getClass(), ResponseStatus.class) != null) {
            throw error;
        }
        log.error("Unhandled exception", error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected server error");
        return handleExceptionInternal(error, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servlet) {
            HttpServletRequest http = servlet.getRequest();
            problem.setInstance(URI.create(http.getRequestURI()));
        }
        return super.handleExceptionInternal(error, body, headers, status, request);
    }
}
