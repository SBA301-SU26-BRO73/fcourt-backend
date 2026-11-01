package com.fcourt.auth.superadmin;

import com.fcourt.auth.superadmin.SuperAdminDtos.DocumentResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code users}, {@code user_documents} and {@code user_account_action_logs}. */
@Repository
class SuperAdminRepository {
    private static final String USER_COLUMNS =
            "SELECT id, email, full_name, phone, role, status, created_at FROM users ";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    SuperAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    record UserRecord(long id, String email, String fullName, String phone, String role, String status,
                      OffsetDateTime createdAt) {
    }

    Optional<UserRecord> find(long id) {
        return jdbc.query(USER_COLUMNS + "WHERE id = ? AND deleted_at IS NULL", this::user, id)
                .stream().findFirst();
    }

    /** Row lock serialises concurrent decisions on the same account. */
    Optional<UserRecord> findForUpdate(long id) {
        return jdbc.query(USER_COLUMNS + "WHERE id = ? AND deleted_at IS NULL FOR UPDATE", this::user, id)
                .stream().findFirst();
    }

    List<UserRecord> findPendingOwners() {
        return jdbc.query(USER_COLUMNS + "WHERE role = 'ADMIN' AND status = 'PENDING_APPROVAL' "
                + "AND deleted_at IS NULL ORDER BY created_at, id", this::user);
    }

    Map<Long, List<DocumentResponse>> documentsOf(Collection<Long> userIds) {
        Map<Long, List<DocumentResponse>> documents = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return documents;
        }
        named.query("SELECT id, user_id, url, doc_type FROM user_documents WHERE user_id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", userIds), (ResultSet row) -> {
                    documents.computeIfAbsent(row.getLong("user_id"), key -> new ArrayList<>())
                            .add(new DocumentResponse(row.getLong("id"), row.getString("url"),
                                    row.getString("doc_type")));
                });
        return documents;
    }

    void updateStatus(long id, String status, Instant at) {
        jdbc.update("UPDATE users SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(at), id);
    }

    void logAction(long targetUserId, long performedById, String action, String previousStatus,
                   String newStatus, String reason, Instant at) {
        // created_at is "timestamp without time zone": the audit trail is stored in UTC.
        jdbc.update("""
                INSERT INTO user_account_action_logs
                    (target_user_id, performed_by_id, action, previous_status, new_status, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                targetUserId, performedById, action, previousStatus, newStatus, reason,
                LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private UserRecord user(ResultSet row, int index) throws SQLException {
        return new UserRecord(row.getLong("id"), row.getString("email"), row.getString("full_name"),
                row.getString("phone"), row.getString("role"), row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class));
    }
}
