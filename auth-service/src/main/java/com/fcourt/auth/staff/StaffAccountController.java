package com.fcourt.auth.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class StaffAccountController {
    private final StaffReactivation service;
    private final StaffAccounts accounts;

    StaffAccountController(StaffReactivation service, StaffAccounts accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<StaffReactivation.Account> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.me(jwt));
    }

    @PostMapping("/api/internal/staff-accounts/{id}/reactivate")
    ResponseEntity<StaffReactivation.Account> reactivate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Branch request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.reactivate(jwt, id, request.branchId(), key));
    }

    @PostMapping("/api/internal/staff-accounts")
    ResponseEntity<StaffReactivation.Account> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StaffAccounts.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(accounts.create(jwt, key, request));
    }

    @PutMapping("/api/internal/staff-accounts/{id}")
    ResponseEntity<StaffReactivation.Account> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StaffAccounts.Update request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accounts.update(jwt, id, key, request));
    }

    @DeleteMapping("/api/internal/staff-accounts/{id}")
    ResponseEntity<StaffReactivation.Account> disable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accounts.disable(jwt, id, key));
    }

    record Branch(@Positive long branchId) {}
}
