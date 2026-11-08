package com.fcourt.facility.api;

import com.fcourt.facility.api.ReservationDtos.ReservationResponse;
import com.fcourt.facility.api.ReservationDtos.ReserveRequest;
import com.fcourt.facility.service.ReservationService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-to-service full-court reservation protocol (SCRUM-241). Not a user API: callers present the shared
 * {@code X-Internal-Service-Token}; with no token configured (or a short one) every call answers 503, never open.
 */
@RestController
class ReservationController {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final int MIN_TOKEN_BYTES = 32;

    private final ReservationService reservations;
    private final byte[] token;

    ReservationController(ReservationService reservations,
            @Value("${facility.reservations.internal-token:}") String token) {
        this.reservations = reservations;
        this.token = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/internal/court-reservations")
    ResponseEntity<ReservationResponse> reserve(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
            @Valid @RequestBody ReserveRequest request) {
        authorize(supplied);
        var result = reservations.reserve(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.reservation());
    }

    @PostMapping("/api/internal/court-reservations/{id}/commit")
    ResponseEntity<ReservationResponse> commit(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
            @PathVariable UUID id) {
        authorize(supplied);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reservations.commit(id));
    }

    @PostMapping("/api/internal/court-reservations/{id}/rollback")
    ResponseEntity<ReservationResponse> rollback(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
            @PathVariable UUID id) {
        authorize(supplied);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reservations.rollback(id));
    }

    @GetMapping("/api/internal/court-reservations/{id}")
    ResponseEntity<ReservationResponse> get(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
            @PathVariable UUID id) {
        authorize(supplied);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reservations.get(id));
    }

    private void authorize(String supplied) {
        if (token.length < MIN_TOKEN_BYTES) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Reservation authority is not configured");
        }
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service credential required");
        }
    }
}
