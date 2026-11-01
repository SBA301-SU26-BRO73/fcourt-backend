package com.fcourt.staff.api;

import com.fcourt.staff.service.WeekScheduleService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WeekScheduleController {
    private final WeekScheduleService service;
    WeekScheduleController(WeekScheduleService service) { this.service = service; }

    @GetMapping("/api/staff/schedule")
    ResponseEntity<WeekScheduleView> get(@AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, startDate, endDate));
    }
}
