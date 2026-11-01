package com.fcourt.pos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.pos.api.PosViews;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PosCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public PosCommands(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode POS data", error); }
    }
    public String fingerprint(Object value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public void lock(long actor, UUID key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "pos:" + actor + ":" + key); }
    public Long productReplay(long actor, UUID key, String kind, String fingerprint) {
        var rows = jdbc.queryForList("SELECT * FROM pos_commands WHERE actor_id = ? AND command_key = ?", actor, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!kind.equals(row.get("kind")) || !fingerprint.equals(row.get("fingerprint"))) throw conflict();
        return ((Number) row.get("product_id")).longValue();
    }
    public void recordProduct(long actor, UUID key, String kind, String fingerprint, long product) {
        jdbc.update("INSERT INTO pos_commands(actor_id, command_key, kind, fingerprint, product_id) VALUES (?, ?, ?, ?, ?)", actor, key, kind, fingerprint, product);
    }
    public void stockMovement(long product, long actor, UUID key, String kind, int delta, String reason, Long order) {
        if (delta != 0) jdbc.update("INSERT INTO pos_stock_movements(product_id, actor_id, command_key, kind, quantity_delta, reason, order_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                product, actor, key, kind, delta, reason, order);
    }
    public void event(String type, PosViews.Order order) {
        UUID eventId = UUID.nameUUIDFromBytes((order.orderCode() + ":" + type).getBytes(StandardCharsets.UTF_8));
        enqueue(eventId, order.id(), "EVENT", encode(Map.of("eventId", eventId, "type", type, "version", 1, "order", order)));
    }
    public void enqueue(UUID id, long order, String kind, String payload) {
        jdbc.update("INSERT INTO pos_outbox(event_id, order_id, kind, payload) VALUES (?, ?, ?, ?)", id, order, kind, payload);
    }
    public ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to different metadata"); }
}
