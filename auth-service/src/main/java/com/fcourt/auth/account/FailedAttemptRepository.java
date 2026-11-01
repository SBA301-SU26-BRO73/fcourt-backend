package com.fcourt.auth.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code failed_attempts}. */
@Repository
class FailedAttemptRepository {
    private final JdbcTemplate jdbc;

    FailedAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Attempts(int failures, Instant windowStartedAt, Instant lockedUntil) {
    }

    Optional<Instant> lockedUntil(String key) {
        return jdbc.query("SELECT locked_until FROM failed_attempts WHERE attempt_key = ?",
                        (row, index) -> row.getObject("locked_until", OffsetDateTime.class), key)
                .stream().findFirst().map(at -> at == null ? null : at.toInstant());
    }

    /** Creates the row if missing, then locks it so concurrent failures for one key are counted one by one. */
    Attempts lockOrCreate(String key, Instant now) {
        jdbc.update("INSERT INTO failed_attempts (attempt_key, failures, window_started_at) VALUES (?, 0, ?) "
                + "ON CONFLICT (attempt_key) DO NOTHING", key, Timestamp.from(now));
        return jdbc.queryForObject("SELECT failures, window_started_at, locked_until FROM failed_attempts "
                        + "WHERE attempt_key = ? FOR UPDATE",
                (row, index) -> {
                    var locked = row.getObject("locked_until", OffsetDateTime.class);
                    return new Attempts(row.getInt("failures"),
                            row.getObject("window_started_at", OffsetDateTime.class).toInstant(),
                            locked == null ? null : locked.toInstant());
                }, key);
    }

    void save(String key, Attempts attempts) {
        jdbc.update("UPDATE failed_attempts SET failures = ?, window_started_at = ?, locked_until = ? "
                        + "WHERE attempt_key = ?", attempts.failures(), Timestamp.from(attempts.windowStartedAt()),
                attempts.lockedUntil() == null ? null : Timestamp.from(attempts.lockedUntil()), key);
    }

    void delete(String key) {
        jdbc.update("DELETE FROM failed_attempts WHERE attempt_key = ?", key);
    }
}
