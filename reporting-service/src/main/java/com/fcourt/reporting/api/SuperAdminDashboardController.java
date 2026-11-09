package com.fcourt.reporting.api;

import com.fcourt.reporting.service.SuperAdminDetailsService;
import com.fcourt.reporting.service.SuperAdminDetailsService.Details;
import com.fcourt.reporting.service.SuperAdminOverviewService;
import com.fcourt.reporting.service.SuperAdminOverviewService.Overview;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SuperAdminDashboardController {
    private final SuperAdminOverviewService overview;
    private final SuperAdminDetailsService details;

    SuperAdminDashboardController(SuperAdminOverviewService overview, SuperAdminDetailsService details) {
        this.overview = overview;
        this.details = details;
    }

    /** Platform-wide statistics; SUPER_ADMIN only (enforced in ReportingSecurity). */
    @GetMapping("/api/super-admin/dashboard/overview")
    ResponseEntity<Overview> overview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "" + SuperAdminOverviewService.DEFAULT_MONTHS) int months) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(overview.overview(jwt.getTokenValue(), months));
    }

    /** Lists behind the overview cards: active branches, courts, subscriptions, revenue per plan. SUPER_ADMIN only. */
    @GetMapping("/api/super-admin/dashboard/details")
    ResponseEntity<Details> details(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(details.details(jwt.getTokenValue()));
    }
}
