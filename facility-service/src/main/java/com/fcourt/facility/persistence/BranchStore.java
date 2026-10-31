package com.fcourt.facility.persistence;

import com.fcourt.facility.api.BranchRequest;
import com.fcourt.facility.api.BranchResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BranchStore {
    private static final String SELECT = """
            SELECT b.*, (SELECT count(*) FROM courts c WHERE c.branch_id = b.id AND c.deleted_at IS NULL) AS court_count
            FROM branches b
            """;
    private final JdbcTemplate jdbc;

    public BranchStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BranchResponse> listOwned(long adminId) {
        return jdbc.query(SELECT + " WHERE b.admin_id = ? AND b.deleted_at IS NULL ORDER BY b.id", this::row, adminId);
    }

    /** Live (not deleted) branch regardless of owner. */
    public Optional<BranchResponse> find(long id) {
        return jdbc.query(SELECT + " WHERE b.id = ? AND b.deleted_at IS NULL", this::row, id).stream().findFirst();
    }

    public Optional<BranchResponse> findOwned(long id, long adminId) {
        return find(id).filter(branch -> branch.adminId() == adminId);
    }

    /** Row lock that serializes every change (and event version) of one branch. */
    public boolean lockOwned(long id, long adminId) {
        return !jdbc.queryForList("SELECT id FROM branches WHERE id = ? AND admin_id = ? AND deleted_at IS NULL FOR UPDATE",
                Long.class, id, adminId).isEmpty();
    }

    /** Transaction-scoped lock per owner so concurrent creates cannot exceed the plan limit. */
    public void lockOwner(long adminId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "facility-owner:" + adminId);
    }

    public int countOwned(long adminId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM branches WHERE admin_id = ? AND deleted_at IS NULL",
                Integer.class, adminId);
        return count == null ? 0 : count;
    }

    public long insert(long adminId, BranchRequest r) {
        Long id = jdbc.queryForObject("""
                INSERT INTO branches (admin_id, name, address, ward, district, city, latitude, longitude, phone,
                    open_time, close_time, slot_duration_minutes, bank_name, bank_bin, bank_account_number,
                    bank_account_name, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, adminId, r.name().trim(), r.address().trim(), blankToNull(r.ward()),
                blankToNull(r.district()), r.city().trim(), coordinate(r.latitude()), coordinate(r.longitude()),
                blankToNull(r.phone()), Time.valueOf(r.openTime()), Time.valueOf(r.closeTime()),
                r.slotDurationOrDefault(), r.bankName().trim(), r.bankBin(), r.bankAccountNumber(),
                r.bankAccountName().trim(), r.status() == null ? "ACTIVE" : r.status());
        return id;
    }

    public void update(long id, BranchRequest r) {
        jdbc.update("""
                UPDATE branches SET name = ?, address = ?, ward = ?, district = ?, city = ?, latitude = ?,
                    longitude = ?, phone = ?, open_time = ?, close_time = ?, slot_duration_minutes = ?, bank_name = ?,
                    bank_bin = ?, bank_account_number = ?, bank_account_name = ?, status = COALESCE(?, status),
                    updated_at = now()
                WHERE id = ?
                """, r.name().trim(), r.address().trim(), blankToNull(r.ward()), blankToNull(r.district()),
                r.city().trim(), coordinate(r.latitude()), coordinate(r.longitude()), blankToNull(r.phone()),
                Time.valueOf(r.openTime()), Time.valueOf(r.closeTime()), r.slotDurationOrDefault(),
                r.bankName().trim(), r.bankBin(), r.bankAccountNumber(), r.bankAccountName().trim(), r.status(), id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE branches SET deleted_at = now(), updated_at = now() WHERE id = ?", id);
    }

    private static BigDecimal coordinate(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BranchResponse row(ResultSet rs, int n) throws SQLException {
        return new BranchResponse(rs.getLong("id"), rs.getLong("admin_id"), rs.getString("name"),
                rs.getString("address"), rs.getString("ward"), rs.getString("district"), rs.getString("city"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("phone"),
                rs.getTime("open_time").toLocalTime(), rs.getTime("close_time").toLocalTime(),
                rs.getInt("slot_duration_minutes"), rs.getString("bank_name"), rs.getString("bank_bin"),
                rs.getString("bank_account_number"), rs.getString("bank_account_name"), rs.getString("status"),
                rs.getInt("court_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
