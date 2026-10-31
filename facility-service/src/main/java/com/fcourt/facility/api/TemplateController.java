package com.fcourt.facility.api;

import static com.fcourt.facility.api.BranchController.owner;

import com.fcourt.facility.api.TemplateDtos.ApplyResponse;
import com.fcourt.facility.api.TemplateDtos.ApplyTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.PreviewResponse;
import com.fcourt.facility.api.TemplateDtos.SingleTemplateRequest;
import com.fcourt.facility.api.TemplateDtos.SlotSchedule;
import com.fcourt.facility.api.TemplateDtos.TemplateSlot;
import com.fcourt.facility.service.TemplateService;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 30-minute time slot templates (SCRUM-18) and the slot schedule read by discovery-service. */
@RestController
class TemplateController {
    private final TemplateService templates;

    TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @PostMapping("/api/admin/courts/{courtId}/time-slot-templates/preview")
    PreviewResponse preview(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                            @Valid @RequestBody ApplyTemplateRequest request) {
        return templates.preview(owner(jwt), courtId, request);
    }

    @PostMapping("/api/admin/courts/{courtId}/apply-template")
    ApplyResponse apply(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                        @Valid @RequestBody ApplyTemplateRequest request) {
        return templates.apply(owner(jwt), courtId, request);
    }

    @GetMapping("/api/admin/courts/{courtId}/time-slot-templates")
    List<TemplateSlot> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                            @RequestParam(required = false) Integer dayOfWeek) {
        return templates.list(owner(jwt), courtId, dayOfWeek);
    }

    @PostMapping("/api/admin/courts/{courtId}/time-slot-templates")
    @ResponseStatus(HttpStatus.CREATED)
    TemplateSlot create(@AuthenticationPrincipal Jwt jwt, @PathVariable long courtId,
                        @Valid @RequestBody SingleTemplateRequest request) {
        return templates.createOne(owner(jwt), courtId, request);
    }

    @DeleteMapping("/api/admin/time-slot-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        templates.delete(owner(jwt), id);
    }

    /** discovery-service contract (SCRUM-22_AVAILABLE_SLOTS.md); restrict to the service network. */
    @GetMapping("/api/internal/courts/{courtId}/slot-schedule")
    ResponseEntity<SlotSchedule> slotSchedule(@PathVariable long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(templates.schedule(courtId, date));
    }
}
