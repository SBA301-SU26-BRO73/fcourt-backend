package com.fcourt.hold.controller;

import com.fcourt.hold.dto.request.OnlineHoldClaimRequest;
import com.fcourt.hold.dto.request.OnlineHoldReleaseRequest;
import com.fcourt.hold.dto.response.OnlineHoldLease;
import com.fcourt.hold.service.OnlineHolds;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OnlineHoldController {
  private final OnlineHolds holds;

  OnlineHoldController(OnlineHolds holds) {
    this.holds = holds;
  }

  @PostMapping("/api/internal/online-holds/claim")
  ResponseEntity<OnlineHoldLease> claim(
      @RequestHeader(value = "X-Booking-Service-Token", required = false) String secret,
      @Valid @RequestBody OnlineHoldClaimRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(holds.claim(secret, request));
  }

  @PostMapping("/api/internal/online-holds/release")
  ResponseEntity<Void> release(
      @RequestHeader(value = "X-Booking-Service-Token", required = false) String secret,
      @Valid @RequestBody OnlineHoldReleaseRequest request) {
    holds.release(secret, request.holdHash());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
