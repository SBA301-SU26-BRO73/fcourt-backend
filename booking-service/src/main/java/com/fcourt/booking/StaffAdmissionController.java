package com.fcourt.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class StaffAdmissionController {
    private final StaffAdmissions admissions;
    StaffAdmissionController(StaffAdmissions admissions){this.admissions=admissions;}
    @PostMapping("/api/internal/bookings/check-in/preview")
    ResponseEntity<StaffAdmissions.Ticket> preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Preview request) {
        var ticket=admissions.preview(jwt,request.branchId(),request.actorId(),request.checkInCode(),request.date());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ticket);
    }
    @GetMapping("/api/internal/branches/{branch}/offline-tickets")
    ResponseEntity<StaffAdmissions.Roster> roster(@AuthenticationPrincipal Jwt jwt,@PathVariable long branch,@RequestParam LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admissions.roster(jwt,branch,date));
    }
    @PostMapping("/api/internal/bookings/offline-check-in")
    ResponseEntity<StaffAdmissions.Result> replay(@AuthenticationPrincipal Jwt jwt,@RequestBody StaffAdmissions.Offline request,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader(value="X-Staff-Service-Token",required=false)String credential) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admissions.offline(jwt,request,key,credential));
    }
    @PostMapping("/api/internal/bookings/check-in")
    ResponseEntity<StaffAdmissions.Result> online(@AuthenticationPrincipal Jwt jwt,@RequestBody StaffAdmissions.Online request,
            @RequestHeader("Idempotency-Key")String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admissions.online(jwt,request,key));
    }
    record Preview(@Positive long branchId,@Positive long actorId,@NotBlank @Size(max=128)String checkInCode,@NotNull LocalDate date) {
        @Override public String toString(){return "Preview[REDACTED]";}
    }
}
