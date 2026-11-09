package com.fcourt.subscription.stats;

import com.fcourt.subscription.stats.SaasRevenueStats.SaasRevenue;
import com.fcourt.subscription.stats.SubscriptionListStats.SubscriptionOverview;
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
    private final SubscriptionListStats subscriptions;

    SaasRevenueController(SaasRevenueStats stats, SubscriptionListStats subscriptions) {
        this.stats = stats;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/saas-revenue")
    SaasRevenue saasRevenue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(defaultValue = "Asia/Ho_Chi_Minh") String timeZone) {
        return stats.revenue(from, timeZone);
    }

    /** Every subscription plus per-plan PAID revenue; the dashboard classifies ACTIVE/EXPIRED itself. */
    @GetMapping("/subscriptions")
    SubscriptionOverview subscriptions() {
        return subscriptions.overview();
    }
}
