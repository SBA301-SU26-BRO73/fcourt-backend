package com.fcourt.payment;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class UnifiedLedgerController {
    private final UnifiedLedger ledger;
    UnifiedLedgerController(UnifiedLedger ledger){this.ledger=ledger;}
    @GetMapping(value="/api/admin/payments/transactions",params="transactionType")
    ResponseEntity<UnifiedLedger.Page> history(@RequestParam long branchId,@RequestParam String transactionType,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt jwt){
        var result=ledger.history(branchId,fromDate,toDate,status,search,transactionType,page,size,jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Total-Received",result.summary().received().toPlainString())
            .header("X-Total-Refunded",result.summary().refunded().toPlainString()).header("X-Net-Revenue",result.summary().netRevenue().toPlainString()).body(result);
    }
}
