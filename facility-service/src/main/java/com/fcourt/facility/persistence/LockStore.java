package com.fcourt.facility.persistence;

import com.fcourt.facility.api.LockDtos.LockResponse;
import com.fcourt.facility.api.LockDtos.LockType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Court locks (SCRUM-49/111). Cancelled rows are kept for audit and never read as active. */
@Repository
public class LockStore {
    /** Insert payload; reason/notes are internal, the last three text fields are public. */
    public record NewLock(UUID groupId, long branchId, long courtId, LockType type, LocalDateTime startAt,
                          LocalDateTime endAt, String reason, String notes, String publicReason,
                          String contactName, String contactPhone, long createdBy) { }

    private static final String ACTIVE = "cancelled_at IS NULL";
    private final JdbcTemplate jdbc;

    public LockStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LockResponse insert(NewLock lock) {
        return jdbc.queryForObject("""
                INSERT INTO court_locks (group_id, branch_id, court_id, lock_type, start_at, end_at, reason, notes,
                    public_reason, contact_name, contact_phone, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, this::row, lock.groupId(), lock.branchId(), lock.courtId(), lock.type().name(),
                Timestamp.valueOf(lock.startAt()), Timestamp.valueOf(lock.endAt()), lock.reason(), lock.notes(),
                lock.publicReason(), lock.contactName(), lock.contactPhone(), lock.createdBy());
    }

    /** Active locks of the court intersecting [start, end). */
    public List<LockResponse> overlapping(long courtId, LocalDateTime start, LocalDateTime end) {
        return jdbc.query("SELECT * FROM court_locks WHERE court_id = ? AND " + ACTIVE
                        + " AND start_at < ? AND end_at > ? ORDER BY start_at, id",
                this::row, courtId, Timestamp.valueOf(end), Timestamp.valueOf(start));
    }

    public List<LockResponse> listByBranch(long branchId, Long courtId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("SELECT * FROM court_locks WHERE branch_id = ? AND " + ACTIVE
                        + " AND (?::bigint IS NULL OR court_id = ?::bigint)"
                        + " AND (?::timestamp IS NULL OR end_at > ?::timestamp)"
                        + " AND (?::timestamp IS NULL OR start_at < ?::timestamp) ORDER BY start_at, court_id, id",
                this::row, branchId, courtId, courtId, ts(from), ts(from), ts(to), ts(to));
    }

    public Optional<LockResponse> findActive(long id) {
        return jdbc.query("SELECT * FROM court_locks WHERE id = ? AND " + ACTIVE, this::row, id)
                .stream().findFirst();
    }

    public List<LockResponse> findActiveGroup(UUID groupId) {
        return jdbc.query("SELECT * FROM court_locks WHERE group_id = ? AND " + ACTIVE + " ORDER BY court_id",
                this::row, groupId);
    }

    /** Owner of an active lock: {courtId, branchId, adminId}. */
    public Optional<long[]> findOwner(long lockId) {
        return jdbc.query("""
                SELECT l.court_id, l.branch_id, b.admin_id FROM court_locks l JOIN branches b ON b.id = l.branch_id
                WHERE l.id = ? AND l.cancelled_at IS NULL AND b.deleted_at IS NULL
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}, lockId)
                .stream().findFirst();
    }

    /** Owner of an active group: {branchId, adminId}. */
    public Optional<long[]> findGroupOwner(UUID groupId) {
        return jdbc.query("""
                SELECT l.branch_id, b.admin_id FROM court_locks l JOIN branches b ON b.id = l.branch_id
                WHERE l.group_id = ? AND l.cancelled_at IS NULL AND b.deleted_at IS NULL LIMIT 1
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, groupId).stream().findFirst();
    }

    public int cancel(long id, long adminId) {
        return jdbc.update("UPDATE court_locks SET cancelled_at = now(), cancelled_by = ? WHERE id = ? AND "
                + ACTIVE, adminId, id);
    }

    public int cancelGroup(UUID groupId, long adminId) {
        return jdbc.update("UPDATE court_locks SET cancelled_at = now(), cancelled_by = ? WHERE group_id = ? AND "
                + ACTIVE, adminId, groupId);
    }

    /** Active locks of a court touching one venue-local day, for the slot-schedule read model. */
    public List<LockResponse> forDay(long courtId, LocalDate date) {
        return overlapping(courtId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LockResponse row(ResultSet rs, int n) throws SQLException {
        return new LockResponse(rs.getLong("id"), rs.getObject("group_id", UUID.class), rs.getLong("branch_id"),
                rs.getLong("court_id"), LockType.valueOf(rs.getString("lock_type")),
                rs.getTimestamp("start_at").toLocalDateTime(), rs.getTimestamp("end_at").toLocalDateTime(),
                rs.getString("reason"), rs.getString("notes"), rs.getString("public_reason"),
                rs.getString("contact_name"), rs.getString("contact_phone"), rs.getLong("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
