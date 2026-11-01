package com.fcourt.auth.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends a domain event to {@code auth_outbox} inside the caller's transaction,
 * so the event exists if and only if the state change committed.
 * Envelope: {@code {eventId, type, version, data}}.
 */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Envelope(UUID eventId, String type, int version, Object data) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(long aggregateId, String type, int version, Object data) {
        return insert(aggregateId, type, version, data, false);
    }

    /**
     * For events that carry a secret: once published, {@link OutboxRelay} replaces the payload with
     * {@code {eventId, type, scrubbed: true}}, so the secret is not kept in the table after delivery.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID appendSensitive(long aggregateId, String type, int version, Object data) {
        return insert(aggregateId, type, version, data, true);
    }

    private UUID insert(long aggregateId, String type, int version, Object data, boolean sensitive) {
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            payload = json.writeValueAsString(new Envelope(eventId, type, version, data));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialise " + type, error);
        }
        jdbc.update("INSERT INTO auth_outbox(event_id, aggregate_id, event_type, payload, sensitive) "
                + "VALUES (?, ?, ?, ?, ?)", eventId, aggregateId, type, payload, sensitive);
        return eventId;
    }
}
