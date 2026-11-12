package com.fcourt.payment;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/refunds")
class PublicRefundController {
    private final RefundService service;
    private final ReceiptUrls urls;

    PublicRefundController(RefundService service,ReceiptUrls urls) {this.service=service;this.urls=urls;}

    @PostMapping
    ResponseEntity<RefundView> submit(@Valid @RequestBody RefundService.GuestRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(urls.view(service.guestRequest(request)));
    }

    @PostMapping("/lookup")
    ResponseEntity<RefundService.State> lookup(@Valid @RequestBody RefundService.GuestIdentity request) {
        var state=service.guestState(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new RefundService.State(state.bookingId(),state.amount(),state.eligible(),urls.view(state.request())));
    }
}
