package com.fcourt.auth.superadmin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only aggregates over {@code users} for the SUPER_ADMIN platform overview (SCRUM-78). */
@Repository
class PlatformStatsRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    PlatformStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    record OwnerName(long id, String fullName, String email) {
    }

    /** Non-deleted accounts of one role, counted per status. */
    Map<String, Long> countByStatus(String role) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) AS total FROM users WHERE role = ? AND deleted_at IS NULL "
                + "GROUP BY status", row -> {
            counts.put(row.getString("status"), row.getLong("total"));
        }, role);
        return counts;
    }

    /**
     * New CUSTOMER and ADMIN (owner) accounts per calendar month in {@code zone}, from {@code from} onwards.
     * Staff are created by owners and the super admin is seeded, so neither counts as platform growth.
     */
    Map<String, Long> newAccountsByMonth(Instant from, String zone) {
        Map<String, Long> months = new LinkedHashMap<>();
        jdbc.query("""
                SELECT to_char(created_at AT TIME ZONE ?, 'YYYY-MM') AS month, count(*) AS total
                FROM users WHERE role IN ('CUSTOMER', 'ADMIN') AND deleted_at IS NULL AND created_at >= ?
                GROUP BY 1 ORDER BY 1""", row -> {
            months.put(row.getString("month"), row.getLong("total"));
        }, zone, Timestamp.from(from));
        return months;
    }

    List<OwnerName> ownerNames(Collection<Long> ids) {
        List<OwnerName> names = new ArrayList<>();
        if (ids.isEmpty()) {
            return names;
        }
        named.query("SELECT id, full_name, email FROM users WHERE role = 'ADMIN' AND id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", ids), row -> {
                    names.add(new OwnerName(row.getLong("id"), row.getString("full_name"), row.getString("email")));
                });
        return names;
    }
}
