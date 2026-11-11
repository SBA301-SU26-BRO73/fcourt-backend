package com.fcourt.payment;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
class CompactPaymentStatusController {
    private final PaymentStatusService service;
    CompactPaymentStatusController(PaymentStatusService service){this.service=service;}
    @GetMapping(value="/api/payments/{bookingId}/status",params="compact=true")
    ResponseEntity<PaymentStatusCache.Status> status(@PathVariable long bookingId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String auth,
            @RequestHeader(value="X-Hold-Token",required=false) String hold){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(bookingId,auth,hold));
    }
}
