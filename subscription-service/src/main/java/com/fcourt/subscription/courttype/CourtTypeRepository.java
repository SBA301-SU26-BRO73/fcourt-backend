package com.fcourt.subscription.courttype;

import com.fcourt.subscription.courttype.CourtTypeDtos.CourtTypeResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code court_types}; soft-deleted rows ({@code deleted_at} set) are invisible here. */
@Repository
class CourtTypeRepository {
    private static final String COLUMNS = """
            SELECT id, name, name_en, description, icon, color, active, created_at, updated_at
            FROM court_types WHERE deleted_at IS NULL""";

    private final JdbcTemplate jdbc;

    CourtTypeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code active} null means both active and inactive types. */
    List<CourtTypeResponse> findPage(Boolean active, int limit, long offset) {
        return jdbc.query(COLUMNS + " AND (?::boolean IS NULL OR active = ?) ORDER BY id LIMIT ? OFFSET ?",
                this::courtType, active, active, limit, offset);
    }

    long count(Boolean active) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM court_types WHERE deleted_at IS NULL AND (?::boolean IS NULL OR active = ?)",
                Long.class, active, active);
        return total == null ? 0 : total;
    }

    Optional<CourtTypeResponse> find(long id) {
        return jdbc.query(COLUMNS + " AND id = ?", this::courtType, id).stream().findFirst();
    }

    /**
     * Case-insensitive, like the {@code lower(name)} unique index. {@code exceptId} lets an update keep its
     * own name (or change only its case); pass 0 when creating.
     */
    boolean nameInUse(String name, long exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM court_types WHERE lower(name) = lower(?) AND deleted_at IS NULL "
                        + "AND id <> ?)",
                Boolean.class, name, exceptId));
    }

    long insert(String name, String nameEn, String description, String icon, String color, boolean active,
                Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO court_types (name, name_en, description, icon, color, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                name, nameEn, description, icon, color, active, Timestamp.from(at), Timestamp.from(at));
        return id == null ? 0 : id;
    }

    void update(long id, String name, String nameEn, String description, String icon, String color,
                boolean active, Instant at) {
        jdbc.update("""
                UPDATE court_types SET name = ?, name_en = ?, description = ?, icon = ?, color = ?,
                    active = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL""",
                name, nameEn, description, icon, color, active, Timestamp.from(at), id);
    }

    void softDelete(long id, Instant at) {
        jdbc.update("UPDATE court_types SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
                Timestamp.from(at), Timestamp.from(at), id);
    }

    private CourtTypeResponse courtType(ResultSet row, int index) throws SQLException {
        return new CourtTypeResponse(row.getLong("id"), row.getString("name"), row.getString("name_en"),
                row.getString("description"), row.getString("icon"), row.getString("color"),
                row.getBoolean("active"), row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class));
    }
}
