package com.fcourt.booking;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/bookings")
class BookingController {
    private final BookingQueries queries;
    private final ReceiptUrls urls;
    BookingController(BookingQueries queries,ReceiptUrls urls) { this.queries=queries; this.urls=urls; }
    @GetMapping("/my")
    BookingView.Page history(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return urls.view(queries.history(jwt,status,page,size));
    }
    @GetMapping("/{id:[0-9]+}")
    BookingView detail(@PathVariable long id,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Hold-Token",required=false) String holdToken) {
        return urls.view(queries.detail(id,jwt,holdToken));
    }
}
