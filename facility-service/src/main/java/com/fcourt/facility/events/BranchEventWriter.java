package com.fcourt.facility.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a full branch snapshot into the transactional outbox. Must run inside the
 * transaction that changed the branch, its courts or templates, so the event commits
 * (or rolls back) together with the change.
 */
@Component
public class BranchEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String topic;

    public BranchEventWriter(JdbcTemplate jdbc, ObjectMapper json, @Value("${facility.events.topic}") String topic) {
        this.jdbc = jdbc;
        this.json = json;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BranchUpdatedEvent branchChanged(long branchId) {
        // The UPDATE row-locks the branch: versions are allocated strictly increasing per branch,
        // and the snapshot below is read after every earlier committed change of that branch.
        Long version = jdbc.queryForObject(
                "UPDATE branches SET event_version = event_version + 1 WHERE id = ? RETURNING event_version",
                Long.class, branchId);
        if (version == null) throw new IllegalStateException("Branch " + branchId + " does not exist");
        BranchUpdatedEvent event = snapshot(branchId, version);
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO facility_outbox (event_id, topic, event_key, aggregate_version, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, eventId, topic, Long.toString(branchId), version, encode(event));
        return event;
    }

    BranchUpdatedEvent snapshot(long branchId, long version) {
        return jdbc.queryForObject("SELECT * FROM branches WHERE id = ?", (rs, n) -> {
            boolean deleted = rs.getTimestamp("deleted_at") != null;
            boolean active = !deleted && "ACTIVE".equals(rs.getString("status"));
            var latitude = rs.getBigDecimal("latitude");
            var longitude = rs.getBigDecimal("longitude");
            List<BranchUpdatedEvent.CourtSnapshot> courts = deleted ? List.of() : courts(branchId);
            return new BranchUpdatedEvent(branchId, version, rs.getString("name"), rs.getString("address"),
                    rs.getString("city"), rs.getString("district"),
                    latitude == null ? null : latitude.doubleValue(), longitude == null ? null : longitude.doubleValue(),
                    active, deleted, courts);
        }, branchId);
    }

    private List<BranchUpdatedEvent.CourtSnapshot> courts(long branchId) {
        return jdbc.query("""
                SELECT c.id, c.court_type_id, min(t.price) AS min_price, max(t.price) AS max_price
                FROM courts c
                LEFT JOIN time_slot_templates t ON t.court_id = c.id AND t.is_active AND t.deleted_at IS NULL
                WHERE c.branch_id = ? AND c.deleted_at IS NULL AND c.status = 'ACTIVE'
                GROUP BY c.id, c.court_type_id ORDER BY c.id
                """, (rs, n) -> new BranchUpdatedEvent.CourtSnapshot(rs.getLong("id"), rs.getLong("court_type_id"),
                rs.getBigDecimal("min_price"), rs.getBigDecimal("max_price")), branchId);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode branch event", error);
        }
    }
}
