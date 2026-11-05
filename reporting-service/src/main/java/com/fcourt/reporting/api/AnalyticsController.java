package com.fcourt.reporting.api;

import com.fcourt.reporting.service.AnalyticsService;
import com.fcourt.reporting.service.AnalyticsService.Granularity;
import com.fcourt.reporting.service.AnalyticsService.PeakHours;
import com.fcourt.reporting.service.AnalyticsService.Period;
import com.fcourt.reporting.service.AnalyticsService.RevenueChart;
import com.fcourt.reporting.service.AnalyticsService.TopCourts;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner analytics; {@code branchId} omitted = aggregate over every own branch. Authorization: ADMIN role. */
@RestController
class AnalyticsController {
    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Revenue per DAY/WEEK/MONTH bucket plus occupancy (ratio 0..1); defaults: last 30 days, DAY. */
    @GetMapping("/api/admin/dashboard/revenue-chart")
    ResponseEntity<RevenueChart> revenueChart(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Positive Long branchId,
            @RequestParam(required = false) @Positive Long courtId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Granularity granularity) {
        return noStore(analytics.revenueChart(Long.parseLong(jwt.getSubject()), jwt.getTokenValue(), branchId,
                courtId, from, to, granularity));
    }

    /** Top courts by confirmed bookings (then revenue) in the MONTH/QUARTER/YEAR containing {@code date}. */
    @GetMapping("/api/admin/dashboard/analytics/top-courts")
    ResponseEntity<TopCourts> topCourts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Positive Long branchId,
            @RequestParam(required = false) Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer limit) {
        return noStore(analytics.topCourts(Long.parseLong(jwt.getSubject()), jwt.getTokenValue(), branchId, period,
                date, limit));
    }

    /** Booked slots per start hour (0..23) in the MONTH/QUARTER/YEAR containing {@code date}. */
    @GetMapping("/api/admin/dashboard/analytics/peak-hours")
    ResponseEntity<PeakHours> peakHours(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Positive Long branchId,
            @RequestParam(required = false) Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return noStore(analytics.peakHours(Long.parseLong(jwt.getSubject()), jwt.getTokenValue(), branchId, period,
                date));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
