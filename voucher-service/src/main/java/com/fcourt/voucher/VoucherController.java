package com.fcourt.voucher;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/vouchers")
class VoucherController {
    private final VoucherService service;
    VoucherController(VoucherService service) { this.service = service; }

    @GetMapping
    ResponseEntity<Voucher.Page> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, branchId, page, size));
    }

    @GetMapping("/{id}")
    ResponseEntity<Voucher> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    ResponseEntity<Voucher> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VoucherRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    ResponseEntity<Voucher> update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody VoucherRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) { service.delete(jwt, id); }
}
