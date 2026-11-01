package com.fcourt.auth.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code refresh_tokens}; rows are keyed by the SHA-256 of the token. */
@Repository
class RefreshTokenRepository {
    private final JdbcTemplate jdbc;

    RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record StoredToken(long id, long userId, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
    }

    void insert(long userId, String tokenHash, Instant expiresAt, Instant at) {
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
                userId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(at));
    }

    /** Row lock serialises two concurrent refreshes with the same token. */
    Optional<StoredToken> findForUpdate(String tokenHash) {
        return jdbc.query("SELECT id, user_id, expires_at, revoked_at FROM refresh_tokens "
                        + "WHERE token_hash = ? FOR UPDATE",
                (row, index) -> new StoredToken(row.getLong("id"), row.getLong("user_id"),
                        row.getObject("expires_at", OffsetDateTime.class),
                        row.getObject("revoked_at", OffsetDateTime.class)), tokenHash)
                .stream().findFirst();
    }

    void revoke(long id, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(at), id);
    }

    void revokeByHash(String tokenHash, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
                Timestamp.from(at), tokenHash);
    }

    void revokeAllOf(long userId, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(at), userId);
    }
}
