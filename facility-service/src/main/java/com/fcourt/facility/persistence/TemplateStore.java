package com.fcourt.facility.persistence;

import com.fcourt.facility.api.TemplateDtos.TemplateSlot;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TemplateStore {
    private static final String LIVE = "is_active AND deleted_at IS NULL";
    private final JdbcTemplate jdbc;

    public TemplateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TemplateSlot> listLive(long courtId, Integer dayOfWeek) {
        return jdbc.query("SELECT * FROM time_slot_templates WHERE court_id = ? AND " + LIVE
                        + " AND (?::smallint IS NULL OR day_of_week = ?::smallint) ORDER BY day_of_week, start_time",
                this::row, courtId, dayOfWeek, dayOfWeek);
    }

    /** Deactivates every live template of the given day so a new layout replaces it without fragments. */
    public int retireDay(long courtId, int dayOfWeek) {
        return jdbc.update("UPDATE time_slot_templates SET is_active = false, deleted_at = now(), updated_at = now()"
                + " WHERE court_id = ? AND day_of_week = ? AND " + LIVE, courtId, dayOfWeek);
    }

    public boolean overlaps(long courtId, int dayOfWeek, LocalTime start, LocalTime end) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM time_slot_templates WHERE court_id = ?"
                        + " AND day_of_week = ? AND " + LIVE + " AND start_time < ? AND end_time > ?",
                Integer.class, courtId, dayOfWeek, Time.valueOf(end), Time.valueOf(start));
        return count != null && count > 0;
    }

    public TemplateSlot insert(long courtId, int dayOfWeek, LocalTime start, LocalTime end, BigDecimal price) {
        return jdbc.queryForObject("""
                INSERT INTO time_slot_templates (court_id, day_of_week, start_time, end_time, price, is_active)
                VALUES (?, ?, ?, ?, ?, true) RETURNING *
                """, this::row, courtId, dayOfWeek, Time.valueOf(start), Time.valueOf(end), price);
    }

    /** Live template with the owner of its (live) court and branch. */
    public Optional<long[]> findOwner(long templateId) {
        return jdbc.query("""
                SELECT t.court_id, c.branch_id, b.admin_id FROM time_slot_templates t
                JOIN courts c ON c.id = t.court_id JOIN branches b ON b.id = c.branch_id
                WHERE t.id = ? AND t.is_active AND t.deleted_at IS NULL AND c.deleted_at IS NULL AND b.deleted_at IS NULL
                """, (rs, n) -> new long[] {rs.getLong("court_id"), rs.getLong("branch_id"), rs.getLong("admin_id")},
                templateId).stream().findFirst();
    }

    public void retire(long templateId) {
        jdbc.update("UPDATE time_slot_templates SET is_active = false, deleted_at = now(), updated_at = now()"
                + " WHERE id = ?", templateId);
    }

    private TemplateSlot row(ResultSet rs, int n) throws SQLException {
        return new TemplateSlot(rs.getLong("id"), rs.getLong("court_id"), rs.getInt("day_of_week"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getBigDecimal("price"), rs.getBoolean("is_active") && rs.getTimestamp("deleted_at") == null);
    }
}
