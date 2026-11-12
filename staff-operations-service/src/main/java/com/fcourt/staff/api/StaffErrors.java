package com.fcourt.staff.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.fcourt.staff.integration.CheckInRejectedException;

@RestControllerAdvice
class StaffErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> applicationError(ResponseStatusException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason());
        if (error instanceof CheckInRejectedException rejected) problem.setProperty("reason", rejected.reason());
        return ResponseEntity.status(error.getStatusCode()).body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> validationError(Exception error) {
        // Never echo binding errors: a rejected password may be present in them.
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid request fields"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Staff assignment conflicts with existing data"));
    }
}
