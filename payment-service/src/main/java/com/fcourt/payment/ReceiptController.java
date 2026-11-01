package com.fcourt.payment;

import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ReceiptController {
    private final ReceiptService service;
    ReceiptController(ReceiptService service) { this.service=service; }
    @PostMapping(value="/api/payments/{bookingId}/upload-receipt",consumes="multipart/form-data")
    ResponseEntity<ReceiptStatus> upload(@PathVariable @Positive long bookingId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String auth,
            @RequestHeader(value="X-Hold-Token",required=false) String hold,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ReceiptStatus.of(service.upload(bookingId,auth,hold,file)));
    }
    @GetMapping("/api/payments/{bookingId}/status")
    ResponseEntity<ReceiptStatus> status(@PathVariable @Positive long bookingId,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String auth,
            @RequestHeader(value="X-Hold-Token",required=false) String hold) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ReceiptStatus.of(service.status(bookingId,auth,hold)));
    }
    record ReceiptStatus(long id,long bookingId,String status,String receiptImageUrl,
                         java.time.Instant submittedAt,String rejectionReason) {
        static ReceiptStatus of(PaymentRecord p) {
            return new ReceiptStatus(p.id(),p.bookingId(),p.status(),p.receiptImageUrl(),p.submittedAt(),p.rejectionReason());
        }
    }
}
