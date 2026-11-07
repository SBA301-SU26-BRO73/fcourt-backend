package com.fcourt.reporting.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries over {@code booking_facts} for the revenue export (SCRUM-76) and the SUPER_ADMIN overview
 * (SCRUM-78). Revenue follows the owner dashboard's rule (monolith-aligned, see
 * {@code ReadModelStore.revenueOnBookingDate}): the recognized {@code revenue} of CONFIRMED bookings, attributed
 * to the booking (play) date, so exported totals equal the dashboard figures.
 */
@Repository
public class ReportQueryStore {
    private final JdbcTemplate jdbc;

    public ReportQueryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code revenueAt} is when the money was recognized; null when a check-in preceded the payment event. */
    public record RevenueRow(long bookingId, long branchId, LocalDate bookingDate, Instant revenueAt, int slotCount,
                             BigDecimal revenue, String status) { }

    /** Streams CONFIRMED bookings with booking_date in [from, to] (inclusive) of the given branches, oldest first. */
    @Transactional(readOnly = true)
    public void revenueRows(Collection<Long> branchIds, LocalDate from, LocalDate to, Consumer<RevenueRow> sink) {
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT booking_id, branch_id, booking_date, revenue_at, slot_count, revenue, status
                    FROM booking_facts
                    WHERE branch_id = ANY (?) AND status = 'CONFIRMED' AND booking_date >= ? AND booking_date <= ?
                    ORDER BY booking_date, booking_id
                    """);
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(from));
            statement.setDate(3, Date.valueOf(to));
            statement.setFetchSize(500);
            return statement;
        }, rs -> {
            Timestamp recognized = rs.getTimestamp("revenue_at");
            sink.accept(new RevenueRow(rs.getLong("booking_id"), rs.getLong("branch_id"),
                    rs.getDate("booking_date").toLocalDate(), recognized == null ? null : recognized.toInstant(),
                    rs.getInt("slot_count"), rs.getBigDecimal("revenue"), rs.getString("status")));
        });
    }

    /** Platform-wide revenue of CONFIRMED bookings (GMV), all branches. */
    public BigDecimal totalRevenue() {
        return jdbc.queryForObject("SELECT COALESCE(sum(revenue), 0) FROM booking_facts WHERE status = 'CONFIRMED'",
                BigDecimal.class);
    }

    /** CONFIRMED revenue per branch id (branches without revenue are absent). */
    public Map<Long, BigDecimal> revenueByBranch() {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT branch_id, sum(revenue) AS revenue FROM booking_facts WHERE status = 'CONFIRMED'
                GROUP BY branch_id HAVING sum(revenue) > 0""",
                rs -> { result.put(rs.getLong("branch_id"), rs.getBigDecimal("revenue")); });
        return result;
    }

    /** CONFIRMED revenue per booking-date month ({@code yyyy-MM}) from {@code from} (inclusive). */
    public Map<String, BigDecimal> revenueByMonth(LocalDate from) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT to_char(booking_date, 'YYYY-MM') AS month, sum(revenue) AS revenue
                    FROM booking_facts WHERE status = 'CONFIRMED' AND booking_date >= ? GROUP BY 1 ORDER BY 1
                    """);
            statement.setDate(1, Date.valueOf(from));
            return statement;
        }, rs -> { result.put(rs.getString("month"), rs.getBigDecimal("revenue")); });
        return result;
    }

    private static Array ids(java.sql.Connection con, Collection<Long> branchIds) throws java.sql.SQLException {
        return con.createArrayOf("bigint", branchIds.toArray());
    }
}
