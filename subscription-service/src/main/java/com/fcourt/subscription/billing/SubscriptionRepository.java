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

/** JDBC access to {@code subscriptions}; a subscription is live while {@code end_date} has not passed. */
@Repository
class SubscriptionRepository {
    private static final String COLUMNS = """
            SELECT id, admin_id, plan_id, max_courts, max_branches, start_date, end_date
            FROM subscriptions WHERE deleted_at IS NULL AND admin_id = ?""";

    private final JdbcTemplate jdbc;

    SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record SubscriptionRecord(long id, long adminId, long planId, int maxCourts, int maxBranches,
                              LocalDate startDate, LocalDate endDate) {
    }

    /** The live subscription that ends last. */
    Optional<SubscriptionRecord> findLive(long adminId, LocalDate today) {
        return jdbc.query(COLUMNS + " AND end_date >= ? ORDER BY end_date DESC, id DESC LIMIT 1",
                this::subscription, adminId, today).stream().findFirst();
    }

    /** Every live subscription, so a plan change can end them all. */
    List<SubscriptionRecord> findAllLive(long adminId, LocalDate today) {
        return jdbc.query(COLUMNS + " AND end_date >= ? ORDER BY id", this::subscription, adminId, today);
    }

    /** The subscription that ended most recently, shown to owners whose plan lapsed. */
    Optional<SubscriptionRecord> findLatest(long adminId) {
        return jdbc.query(COLUMNS + " ORDER BY end_date DESC, id DESC LIMIT 1", this::subscription, adminId)
                .stream().findFirst();
    }

    long insert(long adminId, long planId, int maxCourts, int maxBranches, LocalDate start, LocalDate end,
                Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO subscriptions (admin_id, plan_id, max_courts, max_branches, start_date, end_date,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                adminId, planId, maxCourts, maxBranches, start, end, Timestamp.from(at), Timestamp.from(at));
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

    /** Serialises concurrent changes to one owner's subscriptions until the transaction ends. */
    void lockOwner(long adminId) {
        jdbc.query("SELECT pg_advisory_xact_lock(8082, ?)", row -> {
        }, (int) (adminId % Integer.MAX_VALUE));
    }

    private SubscriptionRecord subscription(ResultSet row, int index) throws SQLException {
        return new SubscriptionRecord(row.getLong("id"), row.getLong("admin_id"), row.getLong("plan_id"),
                row.getInt("max_courts"), row.getInt("max_branches"),
                row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class));
    }
}
