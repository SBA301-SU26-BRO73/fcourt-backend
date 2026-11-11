package com.fcourt.facility.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.persistence.PricingRuleStore;
import com.fcourt.facility.service.PricingEngine;
import java.math.BigDecimal;
import com.fcourt.facility.persistence.PricingRuleStore;
import com.fcourt.facility.service.PricingEngine;
import java.math.BigDecimal;
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
    private final PricingRuleStore pricingRules;
    private final ObjectMapper json;
    private final String topic;

    public BranchEventWriter(JdbcTemplate jdbc, PricingRuleStore pricingRules, ObjectMapper json, @Value("${facility.events.topic}") String topic) {
        this.jdbc = jdbc;
        this.pricingRules = pricingRules;
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
                    active, deleted, courts, deleted ? null : coverImage(branchId),
                    hhmm(rs.getTime("open_time")), hhmm(rs.getTime("close_time")));
        }, branchId);
    }

    /** First gallery photo, else the first active court photo, else none. */
    private String coverImage(long branchId) {
        return jdbc.query("""
                SELECT url FROM (
                    SELECT url, 0 AS source, sort_order AS ord, id FROM branch_images WHERE branch_id = ?
                    UNION ALL
                    SELECT image_url, 1, 0, id FROM courts
                    WHERE branch_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
                      AND image_url IS NOT NULL AND image_url <> ''
                ) covers ORDER BY source, ord, id LIMIT 1
                """, (rs, n) -> rs.getString(1), branchId, branchId).stream().findFirst().orElse(null);
    }

    private static String hhmm(java.sql.Time time) {
        return time == null ? null : time.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private List<BranchUpdatedEvent.CourtSnapshot> courts(long branchId) {
        return jdbc.query("""
                SELECT c.id, c.court_type_id FROM courts c
                WHERE c.branch_id = ? AND c.deleted_at IS NULL AND c.status = 'ACTIVE' ORDER BY c.id
                """, (rs, n) -> new long[] {rs.getLong("id"), rs.getLong("court_type_id")}, branchId).stream()
                .map(court -> priceRange(court[0], court[1])).toList();
    }

    /**
     * Min/max over every live template slot at its effective price (pricing rules applied, see
     * PricingEngine), so discovery shows the cheapest and dearest slot a customer can really book.
     */
    private BranchUpdatedEvent.CourtSnapshot priceRange(long courtId, long courtTypeId) {
        List<PricingEngine.Rule> rules = pricingRules.listLive(courtId);
        List<BigDecimal> prices = jdbc.query("""
                SELECT day_of_week, start_time, end_time, price FROM time_slot_templates
                WHERE court_id = ? AND is_active AND deleted_at IS NULL
                """, (rs, n) -> PricingEngine.price(rules, rs.getInt("day_of_week"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getBigDecimal("price")).price(), courtId);
        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(null);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(null);
        return new BranchUpdatedEvent.CourtSnapshot(courtId, courtTypeId, min, max);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode branch event", error);
        }
    }
}
