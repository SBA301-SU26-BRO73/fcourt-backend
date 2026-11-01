package com.fcourt.payment;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class CustomerPaymentsController {
    private final CustomerPayments payments;
    CustomerPaymentsController(CustomerPayments payments){this.payments=payments;}
    @GetMapping("/api/payments/my-transactions")
    ResponseEntity<CustomerPayments.Page> history(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.history(jwt,page,size));
    }
}
