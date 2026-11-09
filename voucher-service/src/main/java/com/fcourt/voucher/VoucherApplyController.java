package com.fcourt.voucher;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class VoucherApplyController {
    private final VoucherApplyService service;
    VoucherApplyController(VoucherApplyService service) { this.service = service; }

    @PostMapping("/api/vouchers/apply")
    ResponseEntity<Result> apply(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VoucherApplyRequest request) {
        var application = service.apply(request, jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Result(application.applicationId(),
                application.voucherId(), application.bookingId(), application.originalAmount(), application.discountAmount(),
                application.finalAmount(), application.expiresAt()));
    }
    @PostMapping("/api/vouchers/validate")
    ResponseEntity<VoucherApplyService.Validation> validate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VoucherValidateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.validate(request, jwt));
    }

    @DeleteMapping("/api/vouchers/apply/{bookingId}")
    ResponseEntity<VoucherApplyService.Removal> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.remove(bookingId, jwt));
    }
    record Result(UUID applicationId, long voucherId, long bookingId, BigDecimal originalAmount,
            BigDecimal discountAmount, BigDecimal finalAmount, Instant expiresAt) {}
}
