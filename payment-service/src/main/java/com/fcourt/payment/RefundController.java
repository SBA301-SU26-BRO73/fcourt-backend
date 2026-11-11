package com.fcourt.payment;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refunds")
class RefundController {
    private final RefundService service;
    private final ReceiptUrls urls;
    RefundController(RefundService service,ReceiptUrls urls){this.service=service;this.urls=urls;}
    @PostMapping("/request")
    ResponseEntity<RefundView> request(@Valid @RequestBody RefundService.Request request,@AuthenticationPrincipal Jwt jwt){
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(urls.view(service.request(request,jwt)));
    }
    @GetMapping("/booking/{bookingId}")
    ResponseEntity<RefundService.State> state(@PathVariable long bookingId,@AuthenticationPrincipal Jwt jwt){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state(service.state(bookingId,jwt)));
    }
    private RefundService.State state(RefundService.State s){return new RefundService.State(s.bookingId(),s.amount(),s.eligible(),urls.view(s.request()));}
}
