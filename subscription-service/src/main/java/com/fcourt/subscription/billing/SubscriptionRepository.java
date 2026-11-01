package com.fcourt.subscription.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code subscriptions}. A subscription covers {@code start_date..end_date} (both inclusive):
 * it is live on a day inside that range and upcoming while its start date is still ahead.
 */
@Repository
class SubscriptionRepository {
    private static final String COLUMNS = """
            SELECT id, admin_id, plan_id, max_courts, max_branches, start_date, end_date, starter
            FROM subscriptions WHERE deleted_at IS NULL AND admin_id = ?""";

    private final JdbcTemplate jdbc;

    SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code starter} marks the free subscription an owner gets before buying anything. */
    record SubscriptionRecord(long id, long adminId, long planId, int maxCourts, int maxBranches,
                              LocalDate startDate, LocalDate endDate, boolean starter) {
    }

    /** The subscription covering {@code today}; the one ending last if several do. */
    Optional<SubscriptionRecord> findLive(long adminId, LocalDate today) {
        return jdbc.query(COLUMNS + " AND start_date <= ? AND end_date >= ? "
                + "ORDER BY end_date DESC, id DESC LIMIT 1", this::subscription, adminId, today, today)
                .stream().findFirst();
    }

    /** Live and upcoming subscriptions, in the order they run. */
    List<SubscriptionRecord> findNotEnded(long adminId, LocalDate today) {
        return jdbc.query(COLUMNS + " AND end_date >= ? ORDER BY start_date, end_date, id",
                this::subscription, adminId, today);
    }

    /** The subscription that started most recently, shown to owners whose plan lapsed. */
    Optional<SubscriptionRecord> findLatest(long adminId, LocalDate today) {
        return jdbc.query(COLUMNS + " AND start_date <= ? ORDER BY end_date DESC, id DESC LIMIT 1",
                this::subscription, adminId, today).stream().findFirst();
    }

    long insert(long adminId, long planId, int maxCourts, int maxBranches, LocalDate start, LocalDate end,
                boolean starter, Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO subscriptions (admin_id, plan_id, max_courts, max_branches, start_date, end_date,
                    starter, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                adminId, planId, maxCourts, maxBranches, start, end, starter, Timestamp.from(at), Timestamp.from(at));
        return id == null ? 0 : id;
    }

    /** Renewal of the same plan: later end date and the plan's current limits. */
    void renew(long id, LocalDate newEnd, int maxCourts, int maxBranches, Instant at) {
        jdbc.update("UPDATE subscriptions SET end_date = ?, max_courts = ?, max_branches = ?, updated_at = ? "
                + "WHERE id = ?", newEnd, maxCourts, maxBranches, Timestamp.from(at), id);
    }

    void end(long id, LocalDate endDate, Instant at) {
        jdbc.update("UPDATE subscriptions SET end_date = ?, updated_at = ? WHERE id = ?",
                endDate, Timestamp.from(at), id);
    }

    /** Withdraws a subscription that never ran a full day, keeping the row for history. */
    void softDelete(long id, Instant at) {
        jdbc.update("UPDATE subscriptions SET deleted_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(at), Timestamp.from(at), id);
    }

    /** Serialises concurrent changes to one owner's subscriptions until the transaction ends. */
    void lockOwner(long adminId) {
        jdbc.query("SELECT pg_advisory_xact_lock(8082, ?)", row -> {
        }, (int) (adminId % Integer.MAX_VALUE));
    }

    private SubscriptionRecord subscription(ResultSet row, int index) throws SQLException {
        return new SubscriptionRecord(row.getLong("id"), row.getLong("admin_id"), row.getLong("plan_id"),
                row.getInt("max_courts"), row.getInt("max_branches"),
                row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class),
                row.getBoolean("starter"));
    }
}
