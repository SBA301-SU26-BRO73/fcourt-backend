package com.fcourt.facility.api;

import com.fcourt.facility.api.PricingDtos.CopyRequest;
import com.fcourt.facility.api.PricingDtos.Quote;
import com.fcourt.facility.api.PricingDtos.RuleRequest;
import com.fcourt.facility.api.PricingDtos.RuleResponse;
import com.fcourt.facility.service.PricingService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Court pricing rules (SCRUM-80 peak, SCRUM-82 weekday/weekend), scoped to the owner of the court. */
@RestController
class PricingController {
    private final PricingService pricing;
    private final Owners owners;

    PricingController(PricingService pricing, Owners owners) {
        this.pricing = pricing;
        this.owners = owners;
    }

    @GetMapping("/api/admin/courts/{courtId}/pricing-rules")
    List<RuleResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId) {
        return pricing.list(owners.forCourt(jwt, courtId), courtId);
    }

    @PostMapping("/api/admin/courts/{courtId}/pricing-rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                        @Valid @RequestBody RuleRequest request) {
        return pricing.create(owners.forCourt(jwt, courtId), courtId, request);
    }

    @PutMapping("/api/admin/courts/{courtId}/pricing-rules/{ruleId}")
    RuleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId, @PathVariable long ruleId,
                        @Valid @RequestBody RuleRequest request) {
        return pricing.update(owners.forCourt(jwt, courtId), courtId, ruleId, request);
    }

    @DeleteMapping("/api/admin/courts/{courtId}/pricing-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId, @PathVariable long ruleId) {
        pricing.delete(owners.forCourt(jwt, courtId), courtId, ruleId);
    }

    /** Replaces the weekend rules by copies of the weekday rules (optionally scaled), see PricingService. */
    @PostMapping("/api/admin/courts/{courtId}/pricing-rules/copy-weekday-to-weekend")
    List<RuleResponse> copyWeekdayToWeekend(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                                            @Valid @RequestBody(required = false) CopyRequest request) {
        return pricing.copyWeekdayToWeekend(owners.forCourt(jwt, courtId), courtId,
                request == null ? null : request.priceMultiplier());
    }

    /** Price breakdown for booking-service; restrict to the service network like the other internal reads. */
    @GetMapping("/api/internal/courts/{courtId}/price-quote")
    ResponseEntity<Quote> quote(@PathVariable long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @DateTimeFormat(pattern = "HH:mm[:ss]") LocalTime startTime,
            @RequestParam @DateTimeFormat(pattern = "HH:mm[:ss]") LocalTime endTime) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pricing.quote(courtId, date, startTime, endTime));
    }
}
