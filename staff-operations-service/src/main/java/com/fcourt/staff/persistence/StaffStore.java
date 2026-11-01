package com.fcourt.staff.persistence;

import com.fcourt.staff.api.StaffRequests.Shift;
import com.fcourt.staff.api.StaffView;
import com.fcourt.staff.integration.StaffUpstreams.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class StaffStore {
    private final JdbcTemplate jdbc;

    public StaffStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<StaffView> list(long branchId, int limit, int offset) {
        return withShifts(jdbc.query("SELECT * FROM staff WHERE branch_id = ? ORDER BY id LIMIT ? OFFSET ?",
                this::map, branchId, limit, offset));
    }

    public StaffView get(long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM staff WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff assignment not found");
        return withShifts(rows).getFirst();
    }

    public StaffView forUser(long userId) {
        var rows = jdbc.query("SELECT * FROM staff WHERE user_id = ? AND deleted_at IS NULL", this::map, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No active staff assignment");
        return withShifts(rows).getFirst();
    }

    public long insert(Account account, long branchId) {
        return jdbc.queryForObject("""
                INSERT INTO staff(user_id, branch_id, email, full_name, phone)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, account.id(), branchId, account.email(), account.fullName(), account.phone());
    }

    public void update(long id, Account account) {
        jdbc.update("UPDATE staff SET full_name = ?, phone = ?, updated_at = now() WHERE id = ?",
                account.fullName(), account.phone(), id);
    }

    public void disable(long id) {
        jdbc.update("UPDATE staff SET deleted_at = now(), updated_at = now() WHERE id = ? AND deleted_at IS NULL", id);
    }

    public void replaceShifts(long id, List<Shift> shifts) {
        jdbc.update("DELETE FROM staff_work_shifts WHERE staff_id = ?", id);
        jdbc.batchUpdate("INSERT INTO staff_work_shifts(staff_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?)",
                shifts, 28, (statement, shift) -> {
                    statement.setLong(1, id);
                    statement.setInt(2, shift.dayOfWeek());
                    statement.setObject(3, shift.startTime());
                    statement.setObject(4, shift.endTime());
                });
    }

    public void lockCommand(long actor, UUID key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", actor + ":" + key);
    }

    public Long replay(long actor, UUID key, String kind, String fingerprint) {
        var rows = jdbc.queryForList("SELECT * FROM staff_commands WHERE actor_id = ? AND command_key = ?", actor, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!kind.equals(row.get("kind")) || !fingerprint.equals(row.get("fingerprint"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different command");
        }
        return ((Number) row.get("staff_id")).longValue();
    }

    public void record(long actor, UUID key, String kind, String fingerprint, long id) {
        jdbc.update("INSERT INTO staff_commands(actor_id, command_key, kind, fingerprint, staff_id) VALUES (?, ?, ?, ?, ?)",
                actor, key, kind, fingerprint, id);
        jdbc.update("INSERT INTO staff_audit(actor_id, staff_id, action, command_key) VALUES (?, ?, ?, ?)", actor, id, kind, key);
    }

    private StaffView map(ResultSet row, int number) throws SQLException {
        return new StaffView(row.getLong("id"), row.getLong("user_id"), row.getLong("branch_id"), row.getString("email"),
                row.getString("full_name"), row.getString("phone"), row.getTimestamp("deleted_at") == null,
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), List.of());
    }

    private List<StaffView> withShifts(List<StaffView> staff) {
        if (staff.isEmpty()) return staff;
        String parameters = String.join(",", java.util.Collections.nCopies(staff.size(), "?"));
        Map<Long, List<Shift>> shifts = new HashMap<>();
        jdbc.query("SELECT * FROM staff_work_shifts WHERE staff_id IN (" + parameters
                        + ") ORDER BY day_of_week, start_time", row -> {
                    shifts.computeIfAbsent(row.getLong("staff_id"), ignored -> new ArrayList<>()).add(
                            new Shift(row.getInt("day_of_week"), row.getObject("start_time", LocalTime.class),
                                    row.getObject("end_time", LocalTime.class)));
                }, staff.stream().map(StaffView::id).toArray());
        return staff.stream().map(row -> new StaffView(row.id(), row.userId(), row.branchId(), row.email(), row.fullName(),
                row.phone(), row.active(), row.createdAt(), row.updatedAt(),
                List.copyOf(shifts.getOrDefault(row.id(), List.of())))).toList();
    }
}
