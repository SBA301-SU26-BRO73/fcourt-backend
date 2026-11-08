package com.fcourt.facility.persistence;

import com.fcourt.facility.api.ReservationDtos.CancelCause;
import com.fcourt.facility.api.ReservationDtos.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Full-court reservations (SCRUM-241). Every state change runs inside a transaction that first holds the branch row
 * lock ({@link #lockBranch}), the same lock the admin lock API takes, so reserve, commit, rollback, expiry and
 * ordinary locks of one branch are totally ordered.
 */
@Repository
public class ReservationStore {
    public record Reservation(UUID id, Long branchId, Long adminId, String fingerprint, State state,
                              LocalDateTime startAt, LocalDateTime endAt, OffsetDateTime expiresAt,
                              OffsetDateTime committedAt, OffsetDateTime cancelledAt, CancelCause cancelCause,
                              boolean leaseExpired) { }

    public record Expired(UUID id, long branchId) { }

    private static final String SELECT =
            "SELECT *, (state = 'RESERVED' AND expires_at <= clock_timestamp()) AS lease_expired"
                    + " FROM court_reservations WHERE id = ?";
    private final JdbcTemplate jdbc;

    public ReservationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Reservation> find(UUID id) {
        return jdbc.query(SELECT, this::row, id).stream().findFirst();
    }

    public void lockBranch(long branchId) {
        jdbc.queryForList("SELECT id FROM branches WHERE id = ? FOR UPDATE", Long.class, branchId);
    }

    public void insert(UUID id, long branchId, long adminId, String fingerprint, LocalDateTime startAt,
                       LocalDateTime endAt, int ttlSeconds) {
        jdbc.update("""
                INSERT INTO court_reservations (id, branch_id, admin_id, fingerprint, state, start_at, end_at, expires_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, clock_timestamp() + ?::int * interval '1 second')
                """, id, branchId, adminId, fingerprint, Timestamp.valueOf(startAt), Timestamp.valueOf(endAt),
                ttlSeconds);
    }

    /** Locks are group_id = reservation id, PRIVATE_EVENT, expiring with the reservation until it is committed. */
    public void insertLocks(UUID id, long branchId, List<Long> courtIds, LocalDateTime startAt, LocalDateTime endAt,
                            long adminId, String publicReason) {
        for (long courtId : courtIds) {
            jdbc.update("""
                    INSERT INTO court_locks (group_id, branch_id, court_id, lock_type, start_at, end_at, reason,
                        public_reason, created_by, reservation_id, expires_at)
                    SELECT ?, ?, ?, 'PRIVATE_EVENT', ?, ?, ?, ?, ?, r.id, r.expires_at
                    FROM court_reservations r WHERE r.id = ?
                    """, id, branchId, courtId, Timestamp.valueOf(startAt), Timestamp.valueOf(endAt),
                    "Full-court package " + id, publicReason, adminId, id);
        }
    }

    /** Records that a rollback arrived first, so a late reserve with this id is refused. */
    public void insertTombstone(UUID id) {
        jdbc.update("""
                INSERT INTO court_reservations (id, state, cancelled_at, cancel_cause)
                VALUES (?, 'CANCELLED', now(), 'ROLLBACK') ON CONFLICT (id) DO NOTHING
                """, id);
    }

    /** RESERVED to COMMITTED only while the lease is alive; clears the lock leases. False when refused. */
    public boolean commit(UUID id) {
        int changed = jdbc.update("""
                UPDATE court_reservations SET state = 'COMMITTED', committed_at = now(), expires_at = NULL
                WHERE id = ? AND state = 'RESERVED' AND expires_at > clock_timestamp()
                """, id);
        if (changed == 0) return false;
        jdbc.update("UPDATE court_locks SET expires_at = NULL WHERE reservation_id = ? AND cancelled_at IS NULL", id);
        return true;
    }

    /** RESERVED or COMMITTED to CANCELLED, releasing every lock. False when it was already cancelled. */
    public boolean cancel(UUID id, CancelCause cause) {
        int changed = jdbc.update("""
                UPDATE court_reservations SET state = 'CANCELLED', cancelled_at = now(), cancel_cause = ?
                WHERE id = ? AND state <> 'CANCELLED'
                """, cause.name(), id);
        if (changed == 0) return false;
        jdbc.update("UPDATE court_locks SET cancelled_at = now(), cancelled_by = created_by"
                + " WHERE reservation_id = ? AND cancelled_at IS NULL", id);
        return true;
    }

    /** Cancels only an uncommitted reservation whose lease has passed (never a committed one). */
    public boolean expire(UUID id) {
        int changed = jdbc.update("""
                UPDATE court_reservations SET state = 'CANCELLED', cancelled_at = now(), cancel_cause = 'EXPIRED'
                WHERE id = ? AND state = 'RESERVED' AND expires_at <= clock_timestamp()
                """, id);
        if (changed == 0) return false;
        jdbc.update("UPDATE court_locks SET cancelled_at = now(), cancelled_by = created_by"
                + " WHERE reservation_id = ? AND cancelled_at IS NULL", id);
        return true;
    }

    /** Court ids of the reservation, including released ones (the history of what was held). */
    public List<Long> courtIds(UUID id) {
        return jdbc.queryForList("SELECT court_id FROM court_locks WHERE reservation_id = ? ORDER BY court_id",
                Long.class, id);
    }

    /** Uncommitted reservations whose lease has passed. */
    public List<Expired> expired(int limit) {
        return jdbc.query("SELECT id, branch_id FROM court_reservations WHERE state = 'RESERVED'"
                        + " AND expires_at <= clock_timestamp() ORDER BY expires_at LIMIT ?",
                (rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getLong("branch_id")), limit);
    }

    private Reservation row(ResultSet rs, int n) throws SQLException {
        Timestamp start = rs.getTimestamp("start_at");
        Timestamp end = rs.getTimestamp("end_at");
        long branch = rs.getLong("branch_id");
        boolean noBranch = rs.wasNull();
        long admin = rs.getLong("admin_id");
        boolean noAdmin = rs.wasNull();
        String cause = rs.getString("cancel_cause");
        return new Reservation(rs.getObject("id", UUID.class), noBranch ? null : branch, noAdmin ? null : admin,
                rs.getString("fingerprint"), State.valueOf(rs.getString("state")),
                start == null ? null : start.toLocalDateTime(), end == null ? null : end.toLocalDateTime(),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("committed_at", OffsetDateTime.class),
                rs.getObject("cancelled_at", OffsetDateTime.class), cause == null ? null : CancelCause.valueOf(cause),
                rs.getBoolean("lease_expired"));
    }
}
