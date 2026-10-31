package com.fcourt.staff.api;

import com.fcourt.staff.service.StaffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
class StaffController {
    private final StaffService service;

    StaffController(StaffService service) { this.service = service; }

    @GetMapping("/api/admin/staff")
    ResponseEntity<List<StaffView>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @Positive long branchId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, branchId, limit, offset));
    }

    @PostMapping("/api/admin/staff")
    ResponseEntity<StaffView> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody StaffRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(jwt, key, request));
    }

    @PutMapping("/api/admin/staff/{id}")
    ResponseEntity<StaffView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody StaffRequests.Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, key, request));
    }

    @DeleteMapping("/api/admin/staff/{id}")
    ResponseEntity<StaffView> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.disable(jwt, id, key));
    }

    @GetMapping("/api/staff/me")
    ResponseEntity<StaffView> assignment(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.assigned(jwt));
    }
}
