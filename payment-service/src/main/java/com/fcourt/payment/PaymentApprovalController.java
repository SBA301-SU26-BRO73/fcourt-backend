package com.fcourt.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/payments")
class PaymentApprovalController {
    private final PaymentApprovalService service;
    PaymentApprovalController(PaymentApprovalService service) { this.service=service; }
    @GetMapping("/pending")
    ResponseEntity<List<PaymentRecord>> pending(@RequestParam @Positive long branchId,
            @RequestParam(defaultValue="0") @Min(0) int offset,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.pending(branchId,offset,jwt));
    }
    @PutMapping("/{paymentId}/confirm")
    ResponseEntity<PaymentRecord> confirm(@PathVariable @Positive long paymentId,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(paymentId,true,null,jwt));
    }
    @PutMapping("/{paymentId}/reject")
    ResponseEntity<PaymentRecord> reject(@PathVariable @Positive long paymentId,@Valid @RequestBody Rejection body,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(paymentId,false,body.reason(),jwt));
    }
    record Rejection(@NotBlank @Size(max=500) String reason) { }
}
