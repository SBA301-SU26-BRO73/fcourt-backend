package com.fcourt.auth.staff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class StaffAccountController {
    private final StaffReactivation service;
    StaffAccountController(StaffReactivation service){this.service=service;}
    @GetMapping("/api/auth/me")
    ResponseEntity<StaffReactivation.Account> me(@AuthenticationPrincipal Jwt jwt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.me(jwt));}
    @PostMapping("/api/internal/staff-accounts/{id}/reactivate")
    ResponseEntity<StaffReactivation.Account> reactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,
        @RequestHeader("Idempotency-Key")String key,@Valid @RequestBody Branch request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reactivate(jwt,id,request.branchId(),key));
    }
    record Branch(@Positive long branchId) { }
}
