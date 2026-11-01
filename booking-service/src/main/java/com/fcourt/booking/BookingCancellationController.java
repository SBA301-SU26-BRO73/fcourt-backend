package com.fcourt.booking;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/bookings/{id}")
class BookingCancellationController {
    private final BookingCancellation cancellation;
    BookingCancellationController(BookingCancellation cancellation){this.cancellation=cancellation;}
    record Request(String reason) {}
    @GetMapping("/cancellation-policy")
    BookingCancellation.Policy quote(@PathVariable long id,@AuthenticationPrincipal Jwt jwt){return cancellation.quote(id,jwt);}
    @PostMapping("/cancel")
    BookingView cancel(@PathVariable long id,@RequestBody Request request,@AuthenticationPrincipal Jwt jwt){return cancellation.cancel(id,request.reason(),jwt);}
}
