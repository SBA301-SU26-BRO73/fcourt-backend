package com.fcourt.staff.api;

import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.service.WalkInService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class WalkInController {
    private final WalkInService service;

    public WalkInController(WalkInService service) { this.service = service; }

    @PostMapping("/api/staff/walk-in")
    ResponseEntity<BookingGateway.WalkInResult> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody WalkInRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(jwt, key, request));
    }

    @PutMapping("/api/staff/bookings/{id}/checkout")
    ResponseEntity<BookingGateway.CheckoutResult> checkout(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long id, @RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.checkout(jwt, id, key));
    }

    public record WalkInRequest(@Positive long courtId, @NotNull LocalDate date,
                                @NotNull @Size(min = 1, max = 48) List<@NotNull @Positive Long> slotIds,
                                @NotNull @Pattern(regexp = "\\+?[0-9]{9,15}") String guestPhone) { }
}
