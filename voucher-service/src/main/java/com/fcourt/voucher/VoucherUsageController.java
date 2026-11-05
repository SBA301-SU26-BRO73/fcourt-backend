package com.fcourt.voucher;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class VoucherUsageController {
    private final VoucherUsageService service;
    VoucherUsageController(VoucherUsageService service) { this.service = service; }

    @GetMapping("/api/admin/vouchers/{id}/usages")
    ResponseEntity<VoucherUsage.Page> list(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id, jwt, page, size));
    }
}
