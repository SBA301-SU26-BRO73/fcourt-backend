package com.fcourt.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class RedactingDeadLetterPublishingRecovererTest {
    static final String RESET = """
            {"eventId":"b6c9aabf-2f7e-4b50-9d2e-0b1d6f0b9a11","type":"PasswordResetRequestedEvent","version":1,
             "data":{"userId":7,"email":"customer@demo.com","token":"s3cr3t-TOKEN_value","expiresAt":"bad"}}""";

    RedactingDeadLetterPublishingRecoverer recoverer = new RedactingDeadLetterPublishingRecoverer(
            mock(KafkaTemplate.class), (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));

    @Test
    void theDeadLetterKeepsThePayloadButNotTheResetToken() {
        var record = new ConsumerRecord<Object, Object>("fcourt.auth.events", 0, 12, "7", RESET);

        var out = recoverer.createProducerRecord(record, new TopicPartition("fcourt.auth.events.DLT", -1),
                new RecordHeaders(), null, null);

        assertThat(out.topic()).isEqualTo("fcourt.auth.events.DLT");
        assertThat(out.partition()).isNull();
        assertThat(out.key()).isEqualTo("7");
        assertThat((String) out.value()).doesNotContain("s3cr3t-TOKEN_value")
                .isEqualTo(RESET.replace("s3cr3t-TOKEN_value", "[REDACTED]"));
    }

    @Test
    void tokensAreRedactedEvenInPayloadsThatAreNotValidJson() {
        assertThat(RedactingDeadLetterPublishingRecoverer.redact("{\"token\" : \"a\\\"b\", \"token\":\"c\" broken"))
                .isEqualTo("{\"token\" : \"[REDACTED]\", \"token\":\"[REDACTED]\" broken");
    }

    @Test
    void payloadsWithoutATokenAreUnchanged() {
        String payload = "{\"eventId\":\"x\",\"type\":\"OwnerApprovedEvent\",\"data\":{\"tokenCount\":3}}";

        assertThat(RedactingDeadLetterPublishingRecoverer.redact(payload)).isEqualTo(payload);
    }
}
