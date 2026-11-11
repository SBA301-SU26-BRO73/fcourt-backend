package com.fcourt.auth.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code users} and {@code user_documents} for registration, login and profile. */
@Repository
class AccountRepository {
    private static final String COLUMNS =
            "SELECT id, email, password_hash, full_name, phone, role, status, created_at FROM users ";

    private final JdbcTemplate jdbc;

    AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record AccountRecord(long id, String email, String passwordHash, String fullName, String phone, String role,
                         String status, OffsetDateTime createdAt) {
    }

    /** Ids of the active super admins, the recipients of a new owner registration. */
    List<Long> activeSuperAdminIds() {
        return jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE' AND deleted_at IS NULL ORDER BY id",
                Long.class);
    }

    Optional<AccountRecord> findById(long id) {
        return jdbc.query(COLUMNS + "WHERE id = ? AND deleted_at IS NULL", this::account, id)
                .stream().findFirst();
    }

    Optional<AccountRecord> findByEmail(String email) {
        return jdbc.query(COLUMNS + "WHERE email = ? AND deleted_at IS NULL", this::account, email)
                .stream().findFirst();
    }

    /** The email unique constraint also covers soft-deleted rows, so they are not filtered out here. */
    boolean emailExists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)", Boolean.class, email));
    }

    /** {@code exceptUserId} lets a profile update keep the caller's own phone number. */
    boolean phoneInUse(String phone, long exceptUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE phone = ? AND deleted_at IS NULL AND id <> ?)",
                Boolean.class, phone, exceptUserId));
    }

    long insert(String email, String passwordHash, String fullName, String phone, String role, String status,
                Instant at) {
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, phone, role, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                email, passwordHash, fullName, phone, role, status, Timestamp.from(at), Timestamp.from(at));
        return id == null ? 0 : id;
    }

    void insertDocument(long userId, String url, String docType, Instant at) {
        jdbc.update("INSERT INTO user_documents (user_id, url, doc_type, created_at) VALUES (?, ?, ?, ?)",
                userId, url, docType, Timestamp.from(at));
    }

    void updatePassword(long id, String passwordHash, Instant at) {
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = ?, updated_at = ? WHERE id = ?",
                passwordHash, Timestamp.from(at), Timestamp.from(at), id);
    }

    /** Empty when the account is unknown or its password never changed. */
    Optional<Instant> passwordChangedAt(long id) {
        return jdbc.query("SELECT password_changed_at FROM users WHERE id = ?",
                        (row, index) -> row.getObject("password_changed_at", OffsetDateTime.class), id)
                .stream().filter(java.util.Objects::nonNull).findFirst().map(OffsetDateTime::toInstant);
    }

    /** Avatar set at Google sign-in (users.avatar_url, V12); null when the account has none. */
    String avatarUrl(long id) {
        return jdbc.query("SELECT avatar_url FROM users WHERE id = ?", (row, index) -> row.getString("avatar_url"), id)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    void updateAvatar(long id, String url) {
        jdbc.update("UPDATE users SET avatar_url = ? WHERE id = ?", url, id);
    }

    void updateProfile(long id, String fullName, String phone, Instant at) {
        jdbc.update("UPDATE users SET full_name = ?, phone = ?, updated_at = ? WHERE id = ?",
                fullName, phone, Timestamp.from(at), id);
    }

    private AccountRecord account(ResultSet row, int index) throws SQLException {
        return new AccountRecord(row.getLong("id"), row.getString("email"), row.getString("password_hash"),
                row.getString("full_name"), row.getString("phone"), row.getString("role"),
                row.getString("status"), row.getObject("created_at", OffsetDateTime.class));
    }
}
