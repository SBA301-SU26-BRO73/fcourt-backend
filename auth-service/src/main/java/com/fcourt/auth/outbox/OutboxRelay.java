package com.fcourt.auth.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes pending outbox rows to Kafka (at-least-once; consumers dedupe on eventId).
 * A row is marked published only after the broker acknowledged it; on failure the
 * transaction rolls back and the row is retried on the next tick.
 */
@Component
@ConditionalOnProperty(name = "auth.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final int BATCH = 10;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                       @Value("${auth.events.topic}") String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${auth.events.delay-ms:1000}")
    @Transactional(timeout = 35)
    public void publish() {
        // Only one instance relays at a time; the lock is released at commit.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(8081, 1)", Boolean.class))) {
            return;
        }
        var rows = jdbc.queryForList("SELECT event_id, aggregate_id, payload FROM auth_outbox "
                + "WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT " + BATCH + " FOR UPDATE");
        for (var row : rows) {
            try {
                kafka.send(topic, row.get("aggregate_id").toString(), row.get("payload").toString())
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publication interrupted", error);
            } catch (Exception error) {
                throw new IllegalStateException("Outbox acknowledgement unavailable; events retained", error);
            }
            jdbc.update("UPDATE auth_outbox SET published_at = now() WHERE event_id = ?", row.get("event_id"));
        }
    }
}
