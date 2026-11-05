package com.fcourt.reporting.persistence;

import com.fcourt.reporting.events.EventTranslator.BookingFact;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                    slot_count, last_event_type, court_id, court_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (booking_id) DO UPDATE SET
                    status = CASE WHEN booking_facts.status = 'PENDING' THEN EXCLUDED.status
                        WHEN booking_facts.status = 'CONFIRMED' AND EXCLUDED.last_event_type = 'BookingCancelledEvent'
                            THEN 'CANCELLED'
                        ELSE booking_facts.status END,
                    amount = CASE WHEN EXCLUDED.amount > 0 THEN EXCLUDED.amount ELSE booking_facts.amount END,
                    revenue = CASE WHEN booking_facts.status = 'CONFIRMED' AND EXCLUDED.last_event_type = 'BookingCancelledEvent' THEN 0
                        WHEN booking_facts.revenue_at IS NULL AND EXCLUDED.revenue_at IS NOT NULL
                        AND booking_facts.status <> 'CANCELLED' THEN EXCLUDED.revenue ELSE booking_facts.revenue END,
                    revenue_at = CASE WHEN booking_facts.revenue_at IS NULL AND EXCLUDED.revenue_at IS NOT NULL
                        AND booking_facts.status <> 'CANCELLED' THEN EXCLUDED.revenue_at ELSE booking_facts.revenue_at END,
                    booking_date = COALESCE(booking_facts.booking_date, EXCLUDED.booking_date),
                    slot_count = GREATEST(booking_facts.slot_count, EXCLUDED.slot_count),
                    court_id = COALESCE(booking_facts.court_id, EXCLUDED.court_id),
                    court_name = COALESCE(booking_facts.court_name, EXCLUDED.court_name),
                    last_event_type = EXCLUDED.last_event_type,
                    updated_at = now()
                """, fact.bookingId(), fact.branchId(), fact.status(), fact.amount(), fact.revenue(),
                fact.revenueAt() == null ? null : Timestamp.from(fact.revenueAt()),
                fact.bookingDate() == null ? null : Date.valueOf(fact.bookingDate()), fact.slotCount(), fact.type(),
                fact.courtId(), fact.courtName());
        for (LocalTime start : fact.slotStarts()) {
            jdbc.update("""
                    INSERT INTO booking_slot_facts (booking_id, slot_start) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, fact.bookingId(), Time.valueOf(start));
        }
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

    /** Revenue of live bookings dated on the given day (monolith: by booking date, cancelled excluded). */
    public BigDecimal revenueOnBookingDate(Collection<Long> branchIds, LocalDate day) {
        return jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT COALESCE(sum(revenue), 0) FROM booking_facts
                    WHERE branch_id = ANY (?) AND booking_date = ? AND status = 'CONFIRMED'
                    """);
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(day));
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

    /** Restricts analytics to one court: by id (walk-ins) or by branch-unique name (payment events). */
    public record CourtFilter(Long id, String normalizedName) {
        public static final CourtFilter NONE = new CourtFilter(null, null);

        public static String normalize(String name) {
            return name == null ? null : name.strip().toLowerCase(java.util.Locale.ROOT);
        }

        boolean active() {
            return id != null || normalizedName != null;
        }

        String sql(String alias) {
            if (!active()) return "";
            return " AND (" + alias + ".court_id = ?" + (normalizedName == null ? ""
                    : " OR (" + alias + ".court_id IS NULL AND lower(btrim(" + alias + ".court_name)) = ?)") + ")";
        }

        int bind(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
            if (!active()) return index;
            statement.setObject(index++, id, java.sql.Types.BIGINT);
            if (normalizedName != null) statement.setString(index++, normalizedName);
            return index;
        }
    }

    public record DayAggregate(LocalDate day, BigDecimal revenue, long bookings, long bookedSlots) { }

    /** Confirmed bookings per booking date in [from, to]; days without bookings are absent. */
    public List<DayAggregate> dailyTotals(Collection<Long> branchIds, LocalDate from, LocalDate to, CourtFilter court) {
        List<DayAggregate> result = new ArrayList<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT b.booking_date, COALESCE(sum(b.revenue), 0), count(*), COALESCE(sum(b.slot_count), 0)
                    FROM booking_facts b
                    WHERE b.branch_id = ANY (?) AND b.status = 'CONFIRMED' AND b.booking_date BETWEEN ? AND ?
                    """ + court.sql("b") + " GROUP BY b.booking_date ORDER BY b.booking_date");
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(from));
            statement.setDate(3, Date.valueOf(to));
            court.bind(statement, 4);
            return statement;
        }, rs -> { result.add(new DayAggregate(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getLong(3),
                rs.getLong(4))); });
        return result;
    }

    public record CourtAggregate(long branchId, Long courtId, String courtName, long bookings, long bookedSlots,
                                 BigDecimal revenue) { }

    /** Confirmed bookings per court identity (id or name) in [from, to]. Bookings with no court are skipped. */
    public List<CourtAggregate> courtTotals(Collection<Long> branchIds, LocalDate from, LocalDate to) {
        List<CourtAggregate> result = new ArrayList<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT branch_id, court_id, min(court_name), count(*), COALESCE(sum(slot_count), 0),
                           COALESCE(sum(revenue), 0)
                    FROM booking_facts
                    WHERE branch_id = ANY (?) AND status = 'CONFIRMED' AND booking_date BETWEEN ? AND ?
                      AND (court_id IS NOT NULL OR court_name IS NOT NULL)
                    GROUP BY branch_id, court_id, lower(btrim(court_name))
                    """);
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(from));
            statement.setDate(3, Date.valueOf(to));
            return statement;
        }, rs -> {
            long branchId = rs.getLong(1);
            long courtId = rs.getLong(2);
            Long court = rs.wasNull() ? null : courtId; // wasNull() refers to the column read last
            result.add(new CourtAggregate(branchId, court, rs.getString(3), rs.getLong(4), rs.getLong(5),
                    rs.getBigDecimal(6)));
        });
        return result;
    }

    public record HourAggregate(int hour, long bookings, long slots) { }

    /** Confirmed booked slots grouped by the hour the slot starts in (local time), in [from, to]. */
    public List<HourAggregate> hourlyTotals(Collection<Long> branchIds, LocalDate from, LocalDate to,
                                            CourtFilter court) {
        List<HourAggregate> result = new ArrayList<>();
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    SELECT extract(hour FROM s.slot_start)::int, count(DISTINCT b.booking_id), count(*)
                    FROM booking_facts b JOIN booking_slot_facts s ON s.booking_id = b.booking_id
                    WHERE b.branch_id = ANY (?) AND b.status = 'CONFIRMED' AND b.booking_date BETWEEN ? AND ?
                    """ + court.sql("b") + " GROUP BY 1 ORDER BY 1");
            statement.setArray(1, ids(con, branchIds));
            statement.setDate(2, Date.valueOf(from));
            statement.setDate(3, Date.valueOf(to));
            court.bind(statement, 4);
            return statement;
        }, rs -> { result.add(new HourAggregate(rs.getInt(1), rs.getLong(2), rs.getLong(3))); });
        return result;
    }

    private static Array ids(java.sql.Connection con, Collection<Long> branchIds) throws java.sql.SQLException {
        return con.createArrayOf("bigint", branchIds.toArray());
    }
}
