package com.fcourt.notification.kafka;

import com.fcourt.notification.event.PoisonEventException;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure strategy: a record that fails (mail server down, database error) is retried a fixed number of
 * times, then published to {@code <topic>.DLT} and the consumer moves on, so one bad record never blocks
 * the topic. A {@link PoisonEventException} can never succeed and goes to the DLT without retries.
 * Records in a DLT are not replayed automatically: someone has to look at them. They hold the original
 * payload (including password reset tokens), so treat the DLT topics as sensitive.
 */
@Configuration
class KafkaErrorHandlingConfiguration {
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                          @Value("${notification.retry.attempts}") long attempts,
                                          @Value("${notification.retry.interval-ms}") long intervalMs) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, attempts));
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    /** Creates the dead letter topics next to the consumed ones. */
    @Bean
    KafkaAdmin.NewTopics deadLetterTopics(NotificationTopics topics) {
        return new KafkaAdmin.NewTopics(deadLetterTopicsFor(topics.names()).toArray(NewTopic[]::new));
    }

    static List<NewTopic> deadLetterTopicsFor(List<String> topics) {
        return topics.stream()
                .map(topic -> TopicBuilder.name(topic + DLT_SUFFIX).partitions(1).replicas(1).build())
                .toList();
    }
}
