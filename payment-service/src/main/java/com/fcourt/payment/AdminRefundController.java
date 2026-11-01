package com.fcourt.payment;

import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/refunds")
class AdminRefundController {
    private final AdminRefundService service;
    AdminRefundController(AdminRefundService service){this.service=service;}
    record Note(String reason) {}
    @GetMapping("/pending")
    ResponseEntity<List<RefundView>> pending(@RequestParam long branchId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt jwt){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.pending(branchId,page,size,jwt));
    }
    @PutMapping("/{id}/approve")
    ResponseEntity<RefundView> approve(@PathVariable long id,@AuthenticationPrincipal Jwt jwt){return decision(service.decide(id,"APPROVED",null,null,jwt));}
    @PutMapping("/{id}/reject")
    ResponseEntity<RefundView> reject(@PathVariable long id,@RequestBody Note note,@AuthenticationPrincipal Jwt jwt){return decision(service.decide(id,"REJECTED",note.reason(),null,jwt));}
    @PutMapping(value="/{id}/complete",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<RefundView> complete(@PathVariable long id,@RequestPart("file") MultipartFile proof,@AuthenticationPrincipal Jwt jwt){return decision(service.decide(id,"COMPLETED",null,proof,jwt));}
    private ResponseEntity<RefundView> decision(RefundView refund){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(refund);}
}
