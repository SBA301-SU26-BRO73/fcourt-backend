package com.fcourt.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fcourt.notification.mail.EmailDeliveryException;
import com.fcourt.notification.mail.EmailSender;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Kafka, Postgres and Flyway with only the mail server replaced. Needs Docker. Checks what unit
 * tests cannot: the claim rolling back when sending fails, duplicate and redelivered events, and the
 * dead letter topic.
 */
@SpringBootTest
class NotificationFlowIntegrationTest {
    static final String AUTH = "fcourt.auth.events";
    static final String PAYMENT = "fcourt.payment.events";
    static final String BOOKING = "fcourt.booking.events";

    // Singleton containers: they must outlive the cached Spring context and its Kafka listeners,
    // so Ryuk stops them at JVM exit rather than per class.
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        DATABASE.start();
        KAFKA.start();
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailSender sender;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("notification.retry.attempts", () -> "2");
        registry.add("notification.retry.interval-ms", () -> "100");
    }

    @Test
    void ownerApprovalSendsOneEmailAndIsRecorded() {
        UUID eventId = UUID.randomUUID();
        String email = unique("owner");

        kafka.send(AUTH, "17", ownerApproved(eventId, 1_000_000 + (int) (Math.random() * 1_000_000), email));

        verify(sender, timeout(30_000)).send(argThat(sent -> sent.to().equals(email)
                && sent.template().equals("owner-approved")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outcome(eventId)).isEqualTo("SENT"));
    }

    @Test
    void theSameEventDeliveredTwiceSendsOnce() {
        UUID eventId = UUID.randomUUID();
        String email = unique("owner");
        String payload = ownerApproved(eventId, 2_000_000 + (int) (Math.random() * 1_000_000), email);

        String marker = unique("marker");

        kafka.send(AUTH, "k", payload);
        kafka.send(AUTH, "k", payload);
        kafka.send(AUTH, "k", ownerApproved(UUID.randomUUID(), 3_000_000 + (int) (Math.random() * 1_000_000), marker));

        // The marker event is processed after both copies, so by then the duplicate was seen.
        verify(sender, timeout(30_000)).send(argThat(sent -> sent.to().equals(marker)));
        verify(sender, times(1)).send(argThat(sent -> sent.to().equals(email)));
    }

    @Test
    void cancellationsOfTheSameBookingFromDifferentServicesSendOneEmail() {
        long bookingId = 500_000 + (int) (Math.random() * 100_000);
        String email = unique("customer");

        kafka.send(BOOKING, "k", customerCancellation(UUID.randomUUID(), bookingId, email));
        kafka.send(PAYMENT, "k", cancellation(UUID.randomUUID(), bookingId, email));

        verify(sender, timeout(30_000)).send(argThat(sent -> sent.to().equals(email)));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE business_key = ?",
                        Integer.class, "booking-cancelled:" + bookingId)).isEqualTo(1));
        verify(sender, times(1)).send(argThat(sent -> sent.to().equals(email)));
    }

    @Test
    void aBookingEventWithoutARecipientIsRecordedAsSkippedAndNothingIsSent() {
        UUID eventId = UUID.randomUUID();
        long bookingId = 600_000 + (int) (Math.random() * 100_000);

        kafka.send(PAYMENT, "k", cancellation(eventId, bookingId, null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(outcome(eventId)).isEqualTo("SKIPPED_NO_RECIPIENT"));
        verify(sender, never()).send(argThat(sent -> sent.model().containsValue("BK-" + bookingId)));
    }

    @Test
    void aFailingMailServerIsRetriedAndTheEventIsClaimedOnlyOnceItWasSent() {
        UUID eventId = UUID.randomUUID();
        String email = unique("owner");
        doThrow(new EmailDeliveryException("down", null)).doThrow(new EmailDeliveryException("down", null))
                .doNothing().when(sender).send(any());

        kafka.send(AUTH, "k", ownerApproved(eventId, 4_000_000 + (int) (Math.random() * 1_000_000), email));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(outcome(eventId)).isEqualTo("SENT"));
        verify(sender, times(3)).send(argThat(sent -> sent.to().equals(email)));
    }

    @Test
    void aMailServerThatNeverRecoversSendsTheEventToTheDeadLetterTopicWithoutClaimingIt() {
        UUID eventId = UUID.randomUUID();
        String email = unique("owner");
        doThrow(new EmailDeliveryException("down", null)).when(sender).send(any());
        String payload = ownerApproved(eventId, 5_000_000 + (int) (Math.random() * 1_000_000), email);

        kafka.send(AUTH, "k", payload);

        assertThat(deadLetters(AUTH + ".DLT", eventId.toString())).isEqualTo(payload);
        assertThat(outcome(eventId)).isNull();
    }

    @Test
    void anUnreadableRecordGoesStraightToTheDeadLetterTopicAndDoesNotBlockTheTopic() {
        String garbage = "{not json " + UUID.randomUUID();
        UUID after = UUID.randomUUID();
        String email = unique("owner");

        kafka.send(AUTH, "k", garbage);
        kafka.send(AUTH, "k", ownerApproved(after, 6_000_000 + (int) (Math.random() * 1_000_000), email));

        assertThat(deadLetters(AUTH + ".DLT", garbage)).isEqualTo(garbage);
        verify(sender, timeout(30_000)).send(argThat(sent -> sent.to().equals(email)));
    }

    private String outcome(UUID eventId) {
        List<String> rows = jdbc.queryForList("SELECT outcome FROM processed_events WHERE event_id = ?",
                String.class, eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Reads {@code topic} from the beginning until a record containing {@code marker} shows up. */
    private static String deadLetters(String topic, String marker) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            String[] found = new String[1];
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(marker)) {
                        found[0] = record.value();
                    }
                }
                return found[0] != null;
            });
            return found[0];
        }
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.invalid";
    }

    static String ownerApproved(UUID eventId, int userId, String email) {
        return """
                {"eventId":"%s","type":"OwnerApprovedEvent","version":1,
                 "data":{"userId":%d,"approvedBy":16,"approvedAt":"2026-11-01T03:00:00Z",
                         "email":"%s","fullName":"Chu San"}}""".formatted(eventId, userId, email);
    }

    /** The shape booking-service publishes on fcourt.booking.events when the customer cancels. */
    static String customerCancellation(UUID eventId, long bookingId, String email) {
        return """
                {"eventId":"%s","type":"BookingCancelledEvent",
                 "recipient":{"email":"%s","fullName":"An"},
                 "booking":{"id":%d,"bookingId":%d,"bookingCode":"BK-%d","status":"CANCELLED",
                            "reason":"Bận việc","refundAmount":0,"slots":[]}}"""
                .formatted(eventId, email, bookingId, bookingId, bookingId);
    }

    /** The shape payment-service publishes on fcourt.payment.events when an unpaid checkout expires. */
    static String cancellation(UUID eventId, long bookingId, String email) {
        String recipient = email == null ? "" : "\"recipient\":{\"email\":\"%s\",\"fullName\":\"An\"},".formatted(email);
        return """
                {"eventId":"%s","type":"BookingCancelledEvent",%s
                 "payment":{"id":9,"bookingId":%d,"branchId":3,"amount":200000,
                            "checkout":{"bookingId":%d,"bookingCode":"BK-%d","customerName":"An"}}}"""
                .formatted(eventId, recipient, bookingId, bookingId, bookingId);
    }
}
