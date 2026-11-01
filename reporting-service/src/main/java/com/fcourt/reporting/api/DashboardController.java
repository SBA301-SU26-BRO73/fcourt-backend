package com.fcourt.reporting.api;

import com.fcourt.reporting.service.DashboardService;
import com.fcourt.reporting.service.DashboardService.DashboardStats;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {
    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** Owner-scoped statistics; {@code branchId} omitted = aggregate over every own branch. */
    @GetMapping("/api/admin/dashboard/stats")
    ResponseEntity<DashboardStats> stats(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) @Positive Long branchId) {
        DashboardStats stats = dashboard.stats(Long.parseLong(jwt.getSubject()), jwt.getTokenValue(), branchId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats);
    }
}
