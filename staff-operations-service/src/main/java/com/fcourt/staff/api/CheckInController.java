package com.fcourt.staff.api;

import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.service.CheckInService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CheckInController {
    private final CheckInService service;

    CheckInController(CheckInService service) { this.service = service; }

    @GetMapping("/api/staff/schedule/today")
    ResponseEntity<BookingGateway.Schedule> today(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.today(jwt));
    }

    @PostMapping("/api/staff/check-in/verify")
    ResponseEntity<BookingGateway.CheckInResult> verify(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Code request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(jwt, key, request.checkInCode(), "QR"));
    }

    @PostMapping("/api/staff/check-in/preview")
    ResponseEntity<BookingGateway.TicketPreview> preview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Code request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(jwt, request.checkInCode()));
    }

    record Code(@NotBlank @Size(max = 128) String checkInCode) {
        @Override public String toString() { return "Code[REDACTED]"; }
    }

    @PostMapping("/api/staff/check-in/by-booking")
    ResponseEntity<BookingGateway.CheckInResult> byBooking(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody BookingRef request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verifyBooking(jwt, key, request.bookingId()));
    }

    record BookingRef(@jakarta.validation.constraints.Positive long bookingId) { }

    @PostMapping("/api/staff/check-in/manual")
    ResponseEntity<BookingGateway.CheckInResult> manual(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Code request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.verify(jwt, key, request.checkInCode(), "MANUAL"));
    }
}
