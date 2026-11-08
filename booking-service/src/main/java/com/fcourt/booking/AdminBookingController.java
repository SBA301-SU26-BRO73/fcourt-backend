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

    AdminBookingController(AdminBookingQueries queries) { this.queries = queries; }

    @GetMapping("/api/admin/bookings")
    ResponseEntity<AdminBookingView.Page> list(@AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute AdminBookingFilter filter) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.search(jwt, filter));
    }
}
