package com.fcourt.staff.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class BookingCommandStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BookingCommandStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public String fingerprint(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public <T> T replay(long actor, UUID key, String kind, String fingerprint, Class<T> type) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "booking:" + actor + ":" + key);
        var rows = jdbc.queryForList("SELECT kind, fingerprint, result::text FROM staff_booking_commands WHERE actor_id = ? AND command_key = ?", actor, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!kind.equals(row.get("kind")) || !fingerprint.equals(row.get("fingerprint"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different booking command");
        }
        try { return json.readValue((String) row.get("result"), type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid stored command", error); }
    }

    public void complete(long actor, UUID key, String kind, String fingerprint, Object result,
                         long bookingId, Object eventData) {
        jdbc.update("INSERT INTO staff_booking_commands(actor_id, command_key, kind, fingerprint, result) VALUES (?, ?, ?, ?, ?::jsonb)",
                actor, key, kind, fingerprint, encode(result));
        UUID eventId = UUID.nameUUIDFromBytes(("staff:" + actor + ":" + key).getBytes(StandardCharsets.UTF_8));
        String payload = encode(Map.of("eventId", eventId, "type", kind, "version", 1, "data", eventData));
        jdbc.update("INSERT INTO staff_outbox(event_id, booking_id, payload) VALUES (?, ?, ?)", eventId, bookingId, payload);
    }

    public void auditCheckIn(long actor, UUID key, long bookingId, long branchId, String channel, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO staff_check_in_audit(actor_id, booking_id, branch_id, channel, command_key, checked_in_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor, bookingId, branchId, channel, key, java.sql.Timestamp.from(occurredAt));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode command", error); }
    }
}
