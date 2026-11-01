package com.fcourt.auth.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "auth.events.enabled", havingValue = "true", matchIfMissing = true)
class KafkaTopicConfiguration {
    /** Created on startup when missing; an existing topic is left untouched. */
    @Bean
    NewTopic authEventsTopic(@Value("${auth.events.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
