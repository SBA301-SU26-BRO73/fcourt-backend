package com.fcourt.notification.kafka;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * Dead letters without secrets: every {@code "token":"..."} value (the password reset token) is replaced
 * before the record is written to the DLT, so whoever reads the DLT cannot reset someone's password. The
 * rest of the payload stays as it was for diagnosis. Works on the raw text, so it also covers payloads that
 * are not valid JSON. A redacted reset cannot be replayed; the user requests a new link instead.
 */
class RedactingDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer {
    static final String REDACTED = "[REDACTED]";
    private static final Pattern TOKEN = Pattern.compile("(\"token\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")");

    RedactingDeadLetterPublishingRecoverer(KafkaOperations<?, ?> template,
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        super(template, destinationResolver);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
            TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {
        ProducerRecord<Object, Object> out = super.createProducerRecord(record, topicPartition, headers, key, value);
        if (!(out.value() instanceof String payload)) {
            return out;
        }
        return new ProducerRecord<>(out.topic(), out.partition(), out.timestamp(), out.key(), redact(payload),
                out.headers());
    }

    static String redact(String payload) {
        return TOKEN.matcher(payload).replaceAll("$1" + Matcher.quoteReplacement(REDACTED) + "$2");
    }
}
