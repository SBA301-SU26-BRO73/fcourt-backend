package com.fcourt.subscription.stats;

import com.fcourt.subscription.stats.SaasRevenueStats.SaasRevenue;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN only (enforced in SecurityConfiguration); consumed by reporting-service. */
@RestController
@RequestMapping("/api/super-admin/stats")
class SaasRevenueController {
    private final SaasRevenueStats stats;

    SaasRevenueController(SaasRevenueStats stats) {
        this.stats = stats;
    }

    @GetMapping("/saas-revenue")
    SaasRevenue saasRevenue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(defaultValue = "Asia/Ho_Chi_Minh") String timeZone) {
        return stats.revenue(from, timeZone);
    }
}
