package com.fcourt.booking;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminBookingController {
    private final AdminBookingQueries queries;
    private final ReceiptUrls urls;

    private final AdminBookingCancellation cancellation;

    AdminBookingController(AdminBookingQueries queries, AdminBookingCancellation cancellation, ReceiptUrls urls) {
        this.queries = queries;
        this.urls = urls;
        this.cancellation = cancellation;
    }

    /** Idempotent theo trạng thái: hủy lại booking đã CANCELLED trả về kết quả cũ. */
    @org.springframework.web.bind.annotation.PostMapping("/api/admin/bookings/{id}/cancel")
    ResponseEntity<BookingView> cancel(@AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable @jakarta.validation.constraints.Positive long id,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key,
            @Valid @org.springframework.web.bind.annotation.RequestBody
                    com.fcourt.booking.dto.request.AdminCancelRequest request) {
        if (key.isBlank() || key.length() > 128)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-128 characters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(urls.view(cancellation.cancel(id, request.reason(), jwt)));
    }

    @GetMapping("/api/admin/bookings")
    ResponseEntity<AdminBookingView.Page> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute AdminBookingFilter filter) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(urls.view(queries.search(jwt, filter)));
    }
}
