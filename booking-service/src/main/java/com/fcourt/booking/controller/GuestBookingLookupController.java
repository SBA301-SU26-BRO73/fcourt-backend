package com.fcourt.booking.controller;

import com.fcourt.booking.dto.request.GuestLookupRequest;
import com.fcourt.booking.dto.response.GuestBookingResponse;
import com.fcourt.booking.service.GuestBookingLookupService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestBookingLookupController {
    private final GuestBookingLookupService service;

    public GuestBookingLookupController(GuestBookingLookupService service) {
        this.service = service;
    }

    @PostMapping("/api/public/bookings/lookup")
    public ResponseEntity<GuestBookingResponse> lookup(
            @Valid @RequestBody GuestLookupRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(request));
    }
}
