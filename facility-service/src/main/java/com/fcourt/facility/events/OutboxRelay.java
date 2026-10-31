package com.fcourt.facility.events;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes pending outbox rows in id order (at-least-once). A row is marked published only
 * after the broker acknowledged it; the first failure stops the batch to keep per-branch order.
 * Consumers ignore replays/older versions using the snapshot version.
 */
@Component
@ConditionalOnProperty(name = "facility.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${facility.events.relay-delay-ms:1000}")
    public void publishPending() {
        try {
            tx.executeWithoutResult(status -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(-8083)", Boolean.class);
                if (!Boolean.TRUE.equals(locked)) return;
                var rows = jdbc.queryForList("""
                        SELECT id, event_id, topic, event_key, payload::text AS payload FROM facility_outbox
                        WHERE published_at IS NULL ORDER BY id LIMIT 50
                        """);
                for (var row : rows) {
                    try {
                        kafka.send((String) row.get("topic"), (String) row.get("event_key"), (String) row.get("payload"))
                                .get(15, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException failure) {
                        // Leave the row pending; the next run retries it before any later event.
                        log.warn("Branch event {} not delivered yet: {}", row.get("event_id"), failure.toString());
                        return;
                    }
                    jdbc.update("UPDATE facility_outbox SET published_at = now() WHERE id = ?", row.get("id"));
                }
            });
        } catch (DataAccessException error) {
            log.warn("Facility outbox unavailable: {}", error.getMessage());
        }
    }
}
