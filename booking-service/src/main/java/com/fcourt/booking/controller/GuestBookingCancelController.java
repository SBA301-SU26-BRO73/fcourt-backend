package com.fcourt.booking.controller;

import com.fcourt.booking.dto.request.GuestCancelRequest;
import com.fcourt.booking.GuestBookingCancellation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestBookingCancelController {
    private final GuestBookingCancellation cancellation;

    public GuestBookingCancelController(GuestBookingCancellation cancellation) {
        this.cancellation = cancellation;
    }

    @PostMapping("/api/public/bookings/cancel")
    public ResponseEntity<GuestBookingCancellation.Result> cancel(@Valid @RequestBody GuestCancelRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cancellation.cancel(request.bookingCode(), request.phone(), request.reason()));
    }
}
