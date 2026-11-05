package com.fcourt.subscription.stats;

import com.fcourt.subscription.common.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SaaS revenue for the SUPER_ADMIN platform overview (SCRUM-78): the sum of PayOS checkouts that reached
 * {@code PAID} (owners buying a plan). Booking money (GMV) is never counted here, so the two never overlap.
 */
@Service
public class SaasRevenueStats {
    public record MonthAmount(String month, BigDecimal amount) {
    }

    public record SaasRevenue(BigDecimal total, long paidPayments, List<MonthAmount> byMonth) {
    }

    private final JdbcTemplate jdbc;

    SaasRevenueStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All-time total plus the per-month breakdown from {@code from} (inclusive, local midnight of the zone). */
    public SaasRevenue revenue(LocalDate from, String timeZone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (DateTimeException invalid) {
            throw ApiException.badRequest("Invalid timeZone");
        }
        var total = jdbc.queryForObject("""
                SELECT COALESCE(sum(amount), 0) AS total, count(*) AS payments
                FROM subscription_payments WHERE status = 'PAID'""",
                (row, index) -> new BigDecimal[] {row.getBigDecimal("total"), BigDecimal.valueOf(row.getLong("payments"))});
        Map<String, BigDecimal> months = new LinkedHashMap<>();
        jdbc.query("""
                SELECT to_char(paid_at AT TIME ZONE ?, 'YYYY-MM') AS month, sum(amount) AS amount
                FROM subscription_payments WHERE status = 'PAID' AND paid_at >= ?
                GROUP BY 1 ORDER BY 1""", row -> {
            months.put(row.getString("month"), row.getBigDecimal("amount"));
        }, zone.getId(), Timestamp.from(from.atStartOfDay(zone).toInstant()));
        return new SaasRevenue(total[0], total[1].longValue(),
                months.entrySet().stream().map(entry -> new MonthAmount(entry.getKey(), entry.getValue())).toList());
    }
}
