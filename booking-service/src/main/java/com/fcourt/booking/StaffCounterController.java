package com.fcourt.booking;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
class StaffCounterController {
    private final StaffCounter counter;

    StaffCounterController(StaffCounter counter) {
        this.counter = counter;
    }

    @GetMapping("/api/internal/courts/{court}/slot-occupancy")
    ResponseEntity<StaffCounter.Occupancy> occupancy(
            @PathVariable long court, @RequestParam LocalDate date) {
        return ok(counter.occupancy(court, date));
    }

    @GetMapping("/api/internal/branches/{branch}/schedule")
    ResponseEntity<StaffCounter.Schedule> schedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long branch,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return ok(counter.schedule(jwt, branch, startDate, endDate));
    }

    @PostMapping("/api/internal/bookings/walk-in")
    ResponseEntity<StaffCounter.WalkInResult> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Staff-Service-Token", required = false) String credential,
            @RequestBody StaffCounter.WalkIn request) {
        return ok(counter.create(jwt, key, credential, request));
    }

    @PostMapping("/api/internal/bookings/walk-in/reconcile")
    ResponseEntity<StaffCounter.WalkInResult> reconcile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Staff-Service-Token", required = false) String credential,
            @RequestBody StaffCounter.WalkIn request) {
        return ok(counter.reconcile(jwt, key, credential, request));
    }

    @GetMapping("/api/internal/bookings/{id}/checkout-preview")
    ResponseEntity<StaffCounter.CheckoutPreview> preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @RequestParam long branchId,
            @RequestParam long actorId) {
        return ok(counter.preview(jwt, id, branchId, actorId));
    }

    @PutMapping("/api/internal/bookings/{id}/checkout")
    ResponseEntity<StaffCounter.CheckoutResult> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Staff-Service-Token", required = false) String credential,
            @RequestBody StaffCounter.Checkout request) {
        return ok(counter.checkout(jwt, id, key, credential, request));
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
