package com.fcourt.auth.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code password_reset_tokens}; rows are keyed by the SHA-256 of the emailed token. */
@Repository
class PasswordResetRepository {
    private final JdbcTemplate jdbc;

    PasswordResetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ResetToken(long id, long userId, OffsetDateTime expiresAt, OffsetDateTime usedAt) {
    }

    void insert(long userId, String tokenHash, Instant expiresAt, Instant at) {
        jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, created_at) "
                + "VALUES (?, ?, ?, ?)", userId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(at));
    }

    /** True when the user asked for a reset since {@code since}, used as a per-user cooldown. */
    boolean requestedSince(long userId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM password_reset_tokens WHERE user_id = ? AND created_at > ?)",
                Boolean.class, userId, Timestamp.from(since)));
    }

    void invalidateOpenTokens(long userId, Instant at) {
        jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                Timestamp.from(at), userId);
    }

    /** Row lock stops two concurrent resets from spending the same token. */
    Optional<ResetToken> findForUpdate(String tokenHash) {
        return jdbc.query("SELECT id, user_id, expires_at, used_at FROM password_reset_tokens "
                        + "WHERE token_hash = ? FOR UPDATE",
                (row, index) -> new ResetToken(row.getLong("id"), row.getLong("user_id"),
                        row.getObject("expires_at", OffsetDateTime.class),
                        row.getObject("used_at", OffsetDateTime.class)), tokenHash)
                .stream().findFirst();
    }

    void markUsed(long id, Instant at) {
        jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE id = ?", Timestamp.from(at), id);
    }
}
