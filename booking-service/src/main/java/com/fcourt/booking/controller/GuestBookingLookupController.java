package com.fcourt.booking.controller;

import com.fcourt.booking.config.GuestLookupProperties;
import com.fcourt.booking.dto.request.GuestLookupRequest;
import com.fcourt.booking.dto.response.GuestBookingResponse;
import com.fcourt.booking.service.GuestBookingLookupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestBookingLookupController {
    private final GuestBookingLookupService service;
    private final boolean trustForwardedFor;

    public GuestBookingLookupController(GuestBookingLookupService service, GuestLookupProperties props) {
        this.service = service;
        this.trustForwardedFor = props.trustForwardedFor();
    }

    @PostMapping("/api/public/bookings/lookup")
    public ResponseEntity<GuestBookingResponse> lookup(
            @Valid @RequestBody GuestLookupRequest request, HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(request, clientIp(http)));
    }

    /** Sau proxy tin cậy thì lấy phần tử cuối của X-Forwarded-For (do proxy gần nhất thêm vào, client không giả được). */
    private String clientIp(HttpServletRequest http) {
        if (trustForwardedFor) {
            String header = http.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                String[] parts = header.split(",");
                return parts[parts.length - 1].strip();
            }
        }
        return http.getRemoteAddr();
    }
}
