package com.fcourt.payment;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class FreeVoucherPaymentController {
    private final FreeVoucherPayment payments;
    FreeVoucherPaymentController(FreeVoucherPayment payments) { this.payments = payments; }

    @PostMapping("/api/payments/{bookingId}/confirm-free")
    ResponseEntity<PaymentRecord> confirm(@PathVariable long bookingId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.confirm(bookingId, jwt));
    }
}
