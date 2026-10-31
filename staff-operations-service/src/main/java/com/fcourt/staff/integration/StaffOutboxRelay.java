package com.fcourt.staff.integration;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "staff.events.enabled", havingValue = "true", matchIfMissing = true)
public class StaffOutboxRelay {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public StaffOutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                            @Value("${staff.events.topic}") String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${staff.events.delay-ms:1000}")
    @Transactional(timeout = 35)
    public void publish() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(8090, 1)", Boolean.class))) return;
        for (var row : jdbc.queryForList("SELECT * FROM staff_outbox WHERE published_at IS NULL ORDER BY created_at, event_id LIMIT 10 FOR UPDATE")) {
            try {
                kafka.send(topic, row.get("booking_id").toString(), row.get("payload").toString()).get(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publication interrupted", error);
            } catch (Exception error) {
                throw new IllegalStateException("Outbox acknowledgement unavailable; events retained", error);
            }
            jdbc.update("UPDATE staff_outbox SET published_at = now() WHERE event_id = ?", row.get("event_id"));
        }
    }
}
