package com.fcourt.notification.inbox;

import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.push.PushDeliveryService;
import org.springframework.stereotype.Service;

/** Coordinates independent email and push transactions; SNS retry never repeats a committed email send. */
@Service
public class NotificationService {
    private final EmailEventHandler emails;
    private final PushDeliveryService pushes;

    public NotificationService(EmailEventHandler emails, PushDeliveryService pushes) {
        this.emails = emails; this.pushes = pushes;
    }

    public void handle(String payload) {
        EventEnvelope event = emails.handle(payload); // committed before any push can fail and trigger Kafka retry
        pushes.send(event);
    }
}
