package com.fcourt.hold.controller;

import com.fcourt.hold.dto.request.CounterHoldRequest;
import com.fcourt.hold.dto.response.CounterHoldLease;
import com.fcourt.hold.service.CounterHolds;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CounterHoldController {
    private final CounterHolds holds;

    CounterHoldController(CounterHolds holds) {
        this.holds = holds;
    }

    @PostMapping("/api/internal/counter-holds")
    ResponseEntity<CounterHoldLease> reserve(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @RequestHeader(value = "X-Booking-Service-Token", required = false) String credential,
            @Valid @RequestBody CounterHoldRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(holds.reserve(bearer, credential, request));
    }
}
