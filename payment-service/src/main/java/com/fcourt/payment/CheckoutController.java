package com.fcourt.payment;

import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckoutController {
    private final CheckoutService service;

    CheckoutController(CheckoutService service) { this.service = service; }

    @GetMapping("/api/payments/checkout-info/{bookingId}")
    ResponseEntity<CheckoutInfo> checkout(@PathVariable @Positive long bookingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hold-Token", required = false) String holdToken) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.checkout(bookingId, authorization, holdToken));
    }
}
