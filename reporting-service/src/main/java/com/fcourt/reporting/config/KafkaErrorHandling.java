package com.fcourt.reporting.config;

import com.fcourt.reporting.events.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Transient failures (e.g. database down) retry twice at one-second intervals; records that can
 * never be applied skip retries. Both end in {@code <topic>.DLT} for inspection and replay.
 * A failed DLT publication is not acknowledged, so the record is redelivered.
 */
@Configuration
class KafkaErrorHandling {
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // Negative partition: let the producer choose, so the DLT may have fewer partitions than the source.
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
