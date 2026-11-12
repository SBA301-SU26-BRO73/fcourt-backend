package com.fcourt.notification.inapp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code in_app_notifications}. Every read and write is scoped by user id. */
@Repository
public class InAppNotificationRepository {
    private final JdbcTemplate jdbc;

    InAppNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a new row was added, false when the user already has this dedupe key. */
    boolean insert(InAppPlan plan) {
        return jdbc.update("""
                INSERT INTO in_app_notifications (user_id, type, title, body, link, dedupe_key)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, dedupe_key) DO NOTHING""",
                plan.userId(), plan.type(), plan.title(), plan.body(), plan.link(), plan.dedupeKey()) == 1;
    }

    public List<InAppNotification> newest(long userId, int limit, long offset) {
        return jdbc.query("""
                SELECT id, type, title, body, link, read_at, created_at FROM in_app_notifications
                WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""",
                (rs, row) -> new InAppNotification(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), instant(rs.getTimestamp("read_at")),
                        instant(rs.getTimestamp("created_at"))),
                userId, limit, offset);
    }

    public long count(long userId) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM in_app_notifications WHERE user_id = ?",
                Long.class, userId);
        return total == null ? 0 : total;
    }

    public long unread(long userId) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM in_app_notifications WHERE user_id = ? AND read_at IS NULL", Long.class, userId);
        return total == null ? 0 : total;
    }

    /** False when the notification does not exist or belongs to someone else; marking an already read one is fine. */
    public boolean markRead(long userId, long id) {
        jdbc.update("UPDATE in_app_notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL",
                id, userId);
        Long owned = jdbc.queryForObject("SELECT count(*) FROM in_app_notifications WHERE id = ? AND user_id = ?",
                Long.class, id, userId);
        return owned != null && owned > 0;
    }

    public int markAllRead(long userId) {
        return jdbc.update("UPDATE in_app_notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL",
                userId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
