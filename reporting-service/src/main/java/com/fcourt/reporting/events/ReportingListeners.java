package com.fcourt.reporting.events;

import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumer group reporting-service-v1; offsets are committed per record after the DB commit. */
@Component
@ConditionalOnProperty(name = "reporting.kafka.enabled", havingValue = "true", matchIfMissing = true)
class ReportingListeners {
    private final EventProjector projector;

    ReportingListeners(EventProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(id = "reporting-payment", idIsGroup = false, topics = "${reporting.kafka.payment-topic}")
    void payment(ConsumerRecord<String, String> record) {
        projector.bookingEvent(record.topic(), record.value(), Instant.ofEpochMilli(record.timestamp()), true);
    }

    @KafkaListener(id = "reporting-staff", idIsGroup = false, topics = "${reporting.kafka.staff-topic}")
    void staff(ConsumerRecord<String, String> record) {
        projector.bookingEvent(record.topic(), record.value(), Instant.ofEpochMilli(record.timestamp()), false);
    }

    @KafkaListener(id = "reporting-branch", idIsGroup = false, topics = "${reporting.kafka.branch-topic}")
    void branch(ConsumerRecord<String, String> record) {
        projector.branchSnapshot(record.value());
    }
}
