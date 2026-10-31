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
        UUID eventId = UUID.randomUUID();
        String payload;
        try {
            payload = json.writeValueAsString(new Envelope(eventId, type, version, data));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialise " + type, error);
        }
        jdbc.update("INSERT INTO auth_outbox(event_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?)",
                eventId, aggregateId, type, payload);
        return eventId;
    }
}
