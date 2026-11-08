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
    record Result(UUID applicationId, long voucherId, long bookingId, BigDecimal originalAmount,
            BigDecimal discountAmount, BigDecimal finalAmount, Instant expiresAt) {}
}
