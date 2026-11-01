package com.fcourt.reporting.persistence;

import com.fcourt.reporting.events.EventTranslator.BookingFact;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadModelStore {
    private final JdbcTemplate jdbc;

    public ReadModelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the event id was already applied (duplicate delivery) */
    public boolean markProcessed(String eventId, String topic, String type) {
        return jdbc.update("""
                INSERT INTO processed_events (event_id, topic, event_type) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, topic, type) == 1;
    }

    /**
     * Folds one fact into the booking row. Status only leaves PENDING (CONFIRMED/CANCELLED are final),
     * so late or reordered events cannot regress it. Revenue is recognized once, by the first money event.
     */
    public void apply(BookingFact fact) {
        jdbc.update("""
                INSERT INTO booking_facts (booking_id, branch_id, status, amount, revenue, revenue_at, booking_date,
                    slot_count, last_event_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (booking_id) DO UPDATE SET
                    status = CASE WHEN booking_facts.status = 'PENDING' THEN EXCLUDED.status ELSE booking_facts.status END,
                    amount = CASE WHEN EXCLUDED.amount > 0 THEN EXCLUDED.amount ELSE booking_facts.amount END,
                    revenue = CASE WHEN booking_facts.revenue_at IS NULL AND EXCLUDED.revenue_at IS NOT NULL
                        AND booking_facts.status <> 'CANCELLED' THEN EXCLUDED.revenue ELSE booking_facts.revenue END,
                    revenue_at = CASE WHEN booking_facts.revenue_at IS NULL AND EXCLUDED.revenue_at IS NOT NULL
                        AND booking_facts.status <> 'CANCELLED' THEN EXCLUDED.revenue_at ELSE booking_facts.revenue_at END,
                    booking_date = COALESCE(booking_facts.booking_date, EXCLUDED.booking_date),
                    slot_count = GREATEST(booking_facts.slot_count, EXCLUDED.slot_count),
                    last_event_type = EXCLUDED.last_event_type,
                    updated_at = now()
                """, fact.bookingId(), fact.branchId(), fact.status(), fact.amount(), fact.revenue(),
                fact.revenueAt() == null ? null : Timestamp.from(fact.revenueAt()),
                fact.bookingDate() == null ? null : Date.valueOf(fact.bookingDate()), fact.slotCount(), fact.type());
    }

    /** Version guard: only a newer snapshot replaces the stored one; replays are ignored. */
    public void applyBranchSnapshot(long branchId, long version, boolean active, boolean deleted, int activeCourts) {
        jdbc.update("""
                INSERT INTO branch_snapshots (branch_id, version, active, deleted, active_courts) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (branch_id) DO UPDATE SET version = EXCLUDED.version, active = EXCLUDED.active,
                    deleted = EXCLUDED.deleted, active_courts = EXCLUDED.active_courts, updated_at = now()
                WHERE branch_snapshots.version < EXCLUDED.version
                """, branchId, version, active, deleted, activeCourts);
    }

    public record StatusTotals(Map<String, Long> counts, BigDecimal revenue) { }

    public StatusTotals totals(Collection<Long> branchIds) {
        Map<String, Long> counts = new HashMap<>();
        BigDecimal[] revenue = {BigDecimal.ZERO};
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT status, count(*) AS bookings, COALESCE(sum(revenue), 0) AS revenue
                    FROM booking_facts WHERE branch_id = ANY (?) GROUP BY status
                    """);
            statement.setArray(1, ids(con, branchIds));
            return statement;
        }, rs -> {
            counts.put(rs.getString("status"), rs.getLong("bookings"));
            revenue[0] = revenue[0].add(rs.getBigDecimal("revenue"));
        });
        return new StatusTotals(counts, revenue[0]);
    }

    public BigDecimal revenueBetween(Collection<Long> branchIds, Instant from, Instant to) {
        return jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT COALESCE(sum(revenue), 0) FROM booking_facts
                    WHERE branch_id = ANY (?) AND revenue_at >= ? AND revenue_at < ?
                    """);
            statement.setArray(1, ids(con, branchIds));
            statement.setTimestamp(2, Timestamp.from(from));
            statement.setTimestamp(3, Timestamp.from(to));
            return statement;
        }, rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO);
    }

    /** Confirmed 30-minute slots booked for the given day, per branch. */
    public Map<Long, Long> bookedSlots(Collection<Long> branchIds, LocalDate day) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT branch_id, COALESCE(sum(slot_count), 0) FROM booking_facts
                    WHERE branch_id = ANY (?) AND booking_date = ? AND status = 'CONFIRMED' GROUP BY branch_id
                    """);
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(day));
            return statement;
        }, rs -> { result.put(rs.getLong(1), rs.getLong(2)); });
        return result;
    }

    public Map<Long, Integer> activeCourts(Collection<Long> branchIds) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement(
                    "SELECT branch_id, active_courts FROM branch_snapshots WHERE branch_id = ANY (?)");
            statement.setArray(1, ids(con, branchIds));
            return statement;
        }, rs -> { result.put(rs.getLong(1), rs.getInt(2)); });
        return result;
    }

    private static Array ids(java.sql.Connection con, Collection<Long> branchIds) throws java.sql.SQLException {
        return con.createArrayOf("bigint", branchIds.toArray());
    }
}
