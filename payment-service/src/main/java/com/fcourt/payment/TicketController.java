package com.fcourt.payment;

import jakarta.validation.constraints.Positive;
import java.util.List;
import java.time.LocalDate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class TicketController {
    private final ReceiptService receipts;
    TicketController(ReceiptService receipts) {this.receipts=receipts;}
    @GetMapping("/api/payments/{bookingId}/ticket")
    ResponseEntity<Ticket> ticket(@PathVariable @Positive long bookingId,
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader(value="X-Hold-Token",required=false) String hold) {
        var payment=confirmed(bookingId,auth,hold);var info=payment.checkout();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Ticket(bookingId,info.bookingCode(),
                info.branchName(),info.courtName(),info.date(),info.slots(),payment.checkInCode(),payment.qrImageUrl()));
    }
    @GetMapping(value="/api/payments/{bookingId}/ticket/qr",produces=MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(@PathVariable @Positive long bookingId,
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader(value="X-Hold-Token",required=false) String hold) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(TicketQr.png(confirmed(bookingId,auth,hold).checkInCode()));
    }
    private PaymentRecord confirmed(long id,String auth,String hold) {
        var payment=receipts.ticket(id,auth,hold);
        if ("REJECTED".equals(payment.status()) || "CANCELLED".equals(payment.status())) {
            throw new ResponseStatusException(HttpStatus.GONE,"Payment rejected or cancelled");
        }
        if (!"CONFIRMED".equals(payment.status()) || payment.checkInCode()==null || payment.checkout()==null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Ticket not confirmed");
        }
        return payment;
    }
    record Ticket(long bookingId,String bookingCode,String branchName,String courtName,LocalDate date,
                  List<CheckoutInfo.Slot> slots,String checkInCode,String qrImageUrl) { }
}
