package com.fcourt.auth.common;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 errors {@code {type,title,status,detail,instance,errors?}}; detail is user-safe. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> business(ApiException error, WebRequest request) {
        return ResponseEntity.status(error.status()).body(problem(error.status(), error.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException error, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                "Invalid value for '" + error.getName() + "'", request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException error,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        var body = problem(HttpStatus.BAD_REQUEST, "Invalid request fields", request);
        body.setProperty("errors", error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(),
                        "message", String.valueOf(field.getDefaultMessage())))
                .toList());
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException error,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
                "Malformed request body", request));
    }

    private static ProblemDetail problem(HttpStatus status, String detail, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (request instanceof ServletWebRequest servlet) {
            problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
        }
        return problem;
    }
}
