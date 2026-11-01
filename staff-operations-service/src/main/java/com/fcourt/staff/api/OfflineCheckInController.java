package com.fcourt.staff.api;

import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.service.OfflineCheckInService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class OfflineCheckInController {
    private final OfflineCheckInService service;
    OfflineCheckInController(OfflineCheckInService service){this.service=service;}
    @GetMapping("/api/staff/offline/tickets")
    ResponseEntity<OfflineCheckInService.Snapshot> snapshot(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(jwt));
    }
    @PostMapping("/api/staff/check-in/offline")
    ResponseEntity<BookingGateway.CheckInResult> replay(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Replay request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replay(jwt,key,request.permit(),request.occurredAt()));
    }
    record Replay(@NotBlank @Size(max=4096) String permit,@NotNull Instant occurredAt) {
        @Override public String toString(){return "Replay[REDACTED]";}
    }
}
