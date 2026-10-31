package com.fcourt.facility.persistence;

import com.fcourt.facility.api.CourtRequest;
import com.fcourt.facility.api.CourtResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CourtStore {
    /** Live court inside a live branch; carries the owner for authorization. */
    public record OwnedCourt(CourtResponse court, long adminId, String branchStatus) { }

    private final JdbcTemplate jdbc;

    public CourtStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CourtResponse> listByBranch(long branchId) {
        return jdbc.query("SELECT * FROM courts WHERE branch_id = ? AND deleted_at IS NULL ORDER BY id",
                this::row, branchId);
    }

    public Optional<OwnedCourt> find(long courtId) {
        return jdbc.query("""
                SELECT c.*, b.admin_id, b.status AS branch_status FROM courts c
                JOIN branches b ON b.id = c.branch_id
                WHERE c.id = ? AND c.deleted_at IS NULL AND b.deleted_at IS NULL
                """, (rs, n) -> new OwnedCourt(row(rs, n), rs.getLong("admin_id"), rs.getString("branch_status")),
                courtId).stream().findFirst();
    }

    /** Live courts across every live branch of the owner (subscription court limit). */
    public int countOwned(long adminId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM courts c JOIN branches b ON b.id = c.branch_id
                WHERE b.admin_id = ? AND c.deleted_at IS NULL AND b.deleted_at IS NULL
                """, Integer.class, adminId);
        return count == null ? 0 : count;
    }

    public boolean nameTaken(long branchId, String name, Long exceptCourtId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM courts WHERE branch_id = ? AND deleted_at IS NULL
                    AND lower(btrim(name)) = lower(btrim(?)) AND (?::bigint IS NULL OR id <> ?::bigint)
                """, Integer.class, branchId, name, exceptCourtId, exceptCourtId);
        return count != null && count > 0;
    }

    public long insert(long branchId, CourtRequest r) {
        return jdbc.queryForObject("""
                INSERT INTO courts (branch_id, name, court_type_id, description, image_url, default_price_per_hour, status)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, branchId, r.name().trim(), r.courtTypeId(), r.description(), r.imageUrl(),
                r.defaultPricePerHour(), r.status() == null ? "ACTIVE" : r.status());
    }

    public void update(long courtId, CourtRequest r) {
        jdbc.update("""
                UPDATE courts SET name = ?, court_type_id = ?, description = ?, image_url = ?,
                    default_price_per_hour = ?, status = COALESCE(?, status), updated_at = now()
                WHERE id = ?
                """, r.name().trim(), r.courtTypeId(), r.description(), r.imageUrl(), r.defaultPricePerHour(),
                r.status(), courtId);
    }

    public void updateStatus(long courtId, String status) {
        jdbc.update("UPDATE courts SET status = ?, updated_at = now() WHERE id = ?", status, courtId);
    }

    public void softDelete(long courtId) {
        jdbc.update("UPDATE courts SET deleted_at = now(), updated_at = now() WHERE id = ?", courtId);
    }

    CourtResponse row(ResultSet rs, int n) throws SQLException {
        return new CourtResponse(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("name"),
                rs.getLong("court_type_id"), rs.getString("description"), rs.getString("image_url"),
                rs.getBigDecimal("default_price_per_hour"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
