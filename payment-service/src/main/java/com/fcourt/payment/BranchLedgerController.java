package com.fcourt.payment;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class BranchLedgerController {
    private final BranchLedger ledger;
    BranchLedgerController(BranchLedger ledger){this.ledger=ledger;}
    @GetMapping(value="/api/admin/payments/transactions",params="!transactionType")
    ResponseEntity<BranchLedger.Page> history(@RequestParam long branchId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt jwt){
        var result=ledger.history(branchId,fromDate,toDate,status,search,page,size,jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Total-Received",result.summary().received().toPlainString())
            .header("X-Total-Pending",result.summary().pending().toPlainString())
            .header("X-Rejected-Count",Long.toString(result.summary().rejectedCount())).body(result);
    }
}
