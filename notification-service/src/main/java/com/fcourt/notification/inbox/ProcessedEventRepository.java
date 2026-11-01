package com.fcourt.notification.inbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code processed_events}, the idempotency record of this service. */
@Repository
class ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event unless its {@code eventId} or {@code businessKey} was already recorded.
     * Returns true only for the caller that won, which is then the only one allowed to send.
     * Runs in the caller's transaction, so a failed send rolls the claim back and the retry can win.
     */
    boolean claim(UUID eventId, String eventType, String businessKey, Outcome outcome) {
        return jdbc.update("""
                INSERT INTO processed_events (event_id, event_type, business_key, outcome)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                eventId, eventType, businessKey, outcome.name()) == 1;
    }

    enum Outcome {
        SENT, SKIPPED_NO_RECIPIENT
    }
}
