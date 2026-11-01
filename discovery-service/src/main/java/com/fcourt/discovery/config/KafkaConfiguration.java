package com.fcourt.discovery.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfiguration {
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // Never acknowledge recovery when publishing the dead-letter record fails.
        recoverer.setFailIfSendResultIsError(true);
        var backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(600_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class,
                ConstraintViolationException.class, IllegalArgumentException.class,
                DataIntegrityViolationException.class);
        return handler;
    }
}
