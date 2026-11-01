package com.fcourt.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaErrorHandlingConfigurationTest {
    KafkaErrorHandlingConfiguration configuration = new KafkaErrorHandlingConfiguration();

    @Test
    void everyConsumedTopicGetsADeadLetterTopic() {
        var topics = KafkaErrorHandlingConfiguration.deadLetterTopicsFor(
                "fcourt.auth.events, fcourt.payment.events,fcourt.booking.events");

        assertThat(topics).extracting(NewTopic::name).containsExactly(
                "fcourt.auth.events.DLT", "fcourt.payment.events.DLT", "fcourt.booking.events.DLT");
    }

    @Test
    void theErrorHandlerIsTheDeadLetteringDefaultHandler() {
        var handler = configuration.kafkaErrorHandler(mock(KafkaTemplate.class), 3, 2000);

        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
    }
}
