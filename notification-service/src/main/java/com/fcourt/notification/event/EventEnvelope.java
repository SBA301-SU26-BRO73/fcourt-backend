package com.fcourt.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/**
 * Common shell of every FCOURT event: {@code eventId} and {@code type} plus a type specific body.
 * auth-service nests the body under {@code data}; payment and booking services under
 * {@code payment}, {@code refund} or {@code booking}. {@link #root()} gives access to all of them.
 */
public record EventEnvelope(UUID eventId, String type, JsonNode root) {
    public static EventEnvelope parse(ObjectMapper json, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new PoisonEventException("Empty event payload");
        }
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JsonProcessingException error) {
            throw new PoisonEventException("Event payload is not valid JSON");
        }
        String type = root.path("type").asText("");
        if (type.isBlank()) {
            throw new PoisonEventException("Event has no type");
        }
        try {
            return new EventEnvelope(UUID.fromString(root.path("eventId").asText("")), type, root);
        } catch (IllegalArgumentException error) {
            throw new PoisonEventException("Event " + type + " has no valid eventId");
        }
    }
}
