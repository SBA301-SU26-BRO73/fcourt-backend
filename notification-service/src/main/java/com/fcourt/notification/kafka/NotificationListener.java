package com.fcourt.notification.kafka;

import com.fcourt.notification.inbox.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes the auth, payment and booking event topics (see {@code notification.topics}). */
@Component
@ConditionalOnProperty(name = "notification.enabled", havingValue = "true", matchIfMissing = true)
class NotificationListener {
    private final NotificationService notifications;

    NotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "#{'${notification.topics}'.split(',')}")
    void onEvent(ConsumerRecord<String, String> record) {
        notifications.handle(record.value());
    }
}
