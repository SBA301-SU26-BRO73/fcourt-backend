package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fcourt.staff.integration.StaffOutboxRelay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class OutboxIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;

    @BeforeAll
    static void database() {
        var data = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
    }

    @BeforeEach
    void reset() { jdbc.execute("TRUNCATE staff_outbox"); }

    @Test
    void realKafkaAcknowledgementMarksEventAndRetainsItsIdentity() throws Exception {
        String topic = "staff-test-" + UUID.randomUUID();
        createTopic(topic);
        UUID event = pending();
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all",
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        try {
            var relay = new StaffOutboxRelay(jdbc, new KafkaTemplate<>(factory), topic);
            transaction.executeWithoutResult(status -> relay.publish());
            transaction.executeWithoutResult(status -> relay.publish());
            assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM staff_outbox", Boolean.class)).isTrue();
            var records = consume(topic, 1);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst()).contains(event.toString());
        } finally { factory.destroy(); }
    }

    @Test
    void brokerFailureLeavesEventPendingForRetry() {
        UUID event = pending();
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        var relay = new StaffOutboxRelay(jdbc, kafka, "staff-test");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> relay.publish()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT event_id FROM staff_outbox WHERE published_at IS NULL", UUID.class)).isEqualTo(event);
    }

    @Test
    void acknowledgementThenDatabaseFailureReplaysSameEventId() throws Exception {
        String topic = "staff-test-" + UUID.randomUUID();
        createTopic(topic);
        UUID event = pending();
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all",
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        try {
            var relay = new StaffOutboxRelay(jdbc, new KafkaTemplate<>(factory), topic);
            jdbc.execute("ALTER TABLE staff_outbox ADD CONSTRAINT fail_publish CHECK (published_at IS NULL)");
            try {
                assertThatThrownBy(() -> transaction.executeWithoutResult(status -> relay.publish()))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            } finally { jdbc.execute("ALTER TABLE staff_outbox DROP CONSTRAINT fail_publish"); }
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM staff_outbox", Boolean.class)).isTrue();
            transaction.executeWithoutResult(status -> relay.publish());
            var records = consume(topic, 2);
            assertThat(records).hasSize(2).allMatch(record -> record.contains(event.toString()));
        } finally { factory.destroy(); }
    }

    UUID pending() {
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_outbox(event_id, booking_id, payload) VALUES (?, 42, ?)",
                event, "{\"eventId\":\"" + event + "\",\"type\":\"BookingCheckedInEvent\"}");
        return event;
    }

    void createTopic(String topic) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
    }

    List<String> consume(String topic, int expected) {
        List<String> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> records.add(record.value()));
            }
        }
        return records;
    }
}
