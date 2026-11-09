package com.fcourt.subscription.stats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Raw subscription rows and per-plan PAID revenue for the SUPER_ADMIN dashboard details (reporting-service).
 * ACTIVE/EXPIRED is decided by the caller from {@code endDate} (inclusive), so no clock lives here.
 */
@Service
public class SubscriptionListStats {
    /** {@code price} is the latest PAID checkout of that owner for that plan; null for the free starter plan. */
    public record SubscriptionRow(long id, long adminId, long planId, String planName, int maxCourts, int maxBranches,
                                  LocalDate startDate, LocalDate endDate, boolean starter, BigDecimal price) {
    }

    public record PlanRevenue(long planId, String planName, long paidCount, BigDecimal revenue) {
    }

    public record SubscriptionOverview(List<SubscriptionRow> subscriptions, List<PlanRevenue> plans) {
    }

    private final JdbcTemplate jdbc;

    SubscriptionListStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SubscriptionOverview overview() {
        List<SubscriptionRow> subscriptions = jdbc.query("""
                SELECT s.id, s.admin_id, s.plan_id, p.name AS plan_name, s.max_courts, s.max_branches,
                       s.start_date, s.end_date, s.starter,
                       (SELECT pay.amount FROM subscription_payments pay
                         WHERE pay.admin_id = s.admin_id AND pay.plan_id = s.plan_id AND pay.status = 'PAID'
                         ORDER BY pay.paid_at DESC NULLS LAST, pay.id DESC LIMIT 1) AS price
                FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
                WHERE s.deleted_at IS NULL
                ORDER BY s.end_date DESC, s.id DESC""",
                (row, index) -> new SubscriptionRow(row.getLong("id"), row.getLong("admin_id"), row.getLong("plan_id"),
                        row.getString("plan_name"), row.getInt("max_courts"), row.getInt("max_branches"),
                        row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class),
                        row.getBoolean("starter"), row.getBigDecimal("price")));
        List<PlanRevenue> plans = jdbc.query("""
                SELECT p.id, p.name, count(pay.id) AS paid_count, COALESCE(sum(pay.amount), 0) AS revenue
                FROM subscription_plans p
                LEFT JOIN subscription_payments pay ON pay.plan_id = p.id AND pay.status = 'PAID'
                GROUP BY p.id, p.name ORDER BY p.id""",
                (row, index) -> new PlanRevenue(row.getLong("id"), row.getString("name"), row.getLong("paid_count"),
                        row.getBigDecimal("revenue")));
        return new SubscriptionOverview(subscriptions, plans);
    }
}
