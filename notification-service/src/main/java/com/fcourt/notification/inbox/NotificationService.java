package com.fcourt.notification.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inapp.InAppEventHandler;
import com.fcourt.notification.mail.EmailDeliveryException;
import com.fcourt.notification.push.PushDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates independent email, in-app and push transactions; a later retry never repeats a committed email
 * send (the claim in {@code processed_events}), an in-app row (unique per user and dedupe key) nor a push
 * (unique per event and device). In-app and push run even when mail delivery fails, so an SMTP outage cannot
 * suppress them; the email failure is rethrown afterwards so Kafka still retries the email.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final ObjectMapper json;
    private final EmailEventHandler emails;
    private final InAppEventHandler inApp;
    private final PushDeliveryService pushes;

    public NotificationService(ObjectMapper json, EmailEventHandler emails, InAppEventHandler inApp,
                               PushDeliveryService pushes) {
        this.json = json; this.emails = emails; this.inApp = inApp; this.pushes = pushes;
    }

    public void handle(String payload) {
        EventEnvelope event = EventEnvelope.parse(json, payload);
        EmailDeliveryException emailFailure = null;
        try {
            emails.handle(payload); // committed before any later step can fail and trigger Kafka retry
        } catch (EmailDeliveryException failure) {
            log.warn("Email for event {} failed; delivering in-app and push before retrying", event.eventId());
            emailFailure = failure;
        }
        inApp.handle(event); // needs no email recipient; its own transaction
        pushes.send(event);
        if (emailFailure != null) throw emailFailure;
    }
}
