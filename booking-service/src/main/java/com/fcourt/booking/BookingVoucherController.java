package com.fcourt.booking;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/bookings/{id}")
class BookingVoucherController {
    private final BookingVoucherBridge bridge;
    BookingVoucherController(BookingVoucherBridge bridge) { this.bridge = bridge; }

    @GetMapping("/voucher-snapshot")
    ResponseEntity<BookingVoucherBridge.Snapshot> snapshot(@PathVariable long id,
            @RequestHeader(value="X-Service-Token",required=false) String token) {
        bridge.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bridge.snapshot(id));
    }
    @PostMapping("/voucher")
    BookingVoucherBridge.Snapshot attach(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Service-Token",required=false) String token,
            @Valid @RequestBody BookingVoucherBridge.Attachment request) {
        bridge.authenticate(token);
        return bridge.attach(id, jwt, request);
    }
    @DeleteMapping("/voucher")
    BookingVoucherBridge.Snapshot detach(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Service-Token",required=false) String token) {
        bridge.authenticate(token);
        return bridge.detach(id, jwt);
    }
    @PostMapping("/payment-freeze")
    BookingVoucherBridge.Snapshot freeze(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Service-Token",required=false) String token,
            @RequestHeader(value="X-Hold-Token",required=false) String hold,
            @Valid @RequestBody BookingVoucherBridge.Freeze request) {
        bridge.authenticate(token);
        return bridge.freeze(id, jwt, hold, request.amount());
    }
}
