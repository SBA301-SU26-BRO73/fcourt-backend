package com.fcourt.booking;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/bookings")
class BookingController {
    private final BookingQueries queries;
    BookingController(BookingQueries queries) { this.queries=queries; }
    @GetMapping("/my")
    BookingView.Page history(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return queries.history(jwt,status,page,size);
    }
    @GetMapping("/{id:[0-9]+}")
    BookingView detail(@PathVariable long id,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Hold-Token",required=false) String holdToken) {
        return queries.detail(id,jwt,holdToken);
    }
}
