package com.fcourt.notification.inbox;

import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inapp.InAppEventHandler;
import com.fcourt.notification.push.PushDeliveryService;
import org.springframework.stereotype.Service;

/**
 * Coordinates independent email, in-app and push transactions; a later retry never repeats a committed email
 * send (the claim in {@code processed_events}) nor an in-app row (unique per user and dedupe key).
 */
@Service
public class NotificationService {
    private final EmailEventHandler emails;
    private final InAppEventHandler inApp;
    private final PushDeliveryService pushes;

    public NotificationService(EmailEventHandler emails, InAppEventHandler inApp, PushDeliveryService pushes) {
        this.emails = emails; this.inApp = inApp; this.pushes = pushes;
    }

    public void handle(String payload) {
        EventEnvelope event = emails.handle(payload); // committed before any later step can fail and trigger Kafka retry
        inApp.handle(event); // needs no email recipient; its own transaction
        pushes.send(event);
    }
}
