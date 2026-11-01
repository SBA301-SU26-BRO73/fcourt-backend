package com.fcourt.discovery.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConfigurationTest {
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("facility.branch-updated.v1", 0, 5L, "1", "{}");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    KafkaConfigurationTest() {
        when(container.isRunning()).thenReturn(true);
        when(template.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void keepsRetryingTransientDatabaseFailuresInsteadOfDeadLettering() {
        var handler = new KafkaConfiguration().kafkaErrorHandler(template);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(handler.handleOne(new DataAccessResourceFailureException("db down"), record, consumer, container))
                    .as("attempt %d", attempt).isFalse();
        }
        verify(template, never()).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    @Test
    void deadLettersDeterministicDatabaseRejectionImmediately() {
        var handler = new KafkaConfiguration().kafkaErrorHandler(template);
        assertThat(handler.handleOne(new DataIntegrityViolationException("bad snapshot"), record, consumer, container)).isTrue();
        verify(template, times(1)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }
}
