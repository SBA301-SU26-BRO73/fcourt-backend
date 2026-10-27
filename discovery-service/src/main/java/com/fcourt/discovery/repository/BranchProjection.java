package com.fcourt.discovery.repository;

import com.fcourt.discovery.model.event.BranchUpdatedEvent;
import com.fcourt.discovery.model.event.BranchProjectionChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BranchProjection {
    private final JdbcTemplate jdbc;
    private final org.springframework.context.ApplicationEventPublisher events;

    public BranchProjection(JdbcTemplate jdbc, org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public void apply(BranchUpdatedEvent event) {
        // The conditional upsert locks the branch until its whole snapshot commits.
        // Tombstones preserve the version so a delayed event cannot resurrect a branch.
        var accepted = jdbc.queryForList("""
                INSERT INTO branch_read_model
                    (id, version, name, address, city, district, search_text,
                     city_key, district_key, latitude, longitude, active)
                VALUES (?, ?, ?, ?, ?, ?, lower(unaccent(?)), lower(unaccent(?)),
                        lower(unaccent(?)), ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    version = EXCLUDED.version, name = EXCLUDED.name,
                    address = EXCLUDED.address, city = EXCLUDED.city,
                    district = EXCLUDED.district, search_text = EXCLUDED.search_text,
                    city_key = EXCLUDED.city_key, district_key = EXCLUDED.district_key,
                    latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                    active = EXCLUDED.active
                WHERE branch_read_model.version < EXCLUDED.version
                RETURNING id
                """, event.branchId(), event.version(), event.name(), event.address(),
                event.city().trim(), event.district() == null ? null : event.district().trim(),
                event.name() + " " + event.address(), event.city().trim(),
                event.district() == null ? null : event.district().trim(),
                event.latitude(), event.longitude(), event.active() && !event.deleted());
        if (accepted.isEmpty()) {
            return;
        }
        events.publishEvent(new BranchProjectionChanged(event.branchId()));
        jdbc.update("DELETE FROM court_read_model WHERE branch_id = ?", event.branchId());
        if (event.deleted()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO court_read_model (branch_id, id, court_type_id, min_price, max_price)
                VALUES (?, ?, ?, ?, ?)
                """, event.courts(), 500, (ps, court) -> {
            ps.setLong(1, event.branchId());
            ps.setLong(2, court.id());
            ps.setLong(3, court.courtTypeId());
            ps.setBigDecimal(4, court.minPrice());
            ps.setBigDecimal(5, court.maxPrice());
        });
    }
}
