package com.fcourt.notification.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EmailPlan;
import com.fcourt.notification.event.EmailPlanner;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inbox.ProcessedEventRepository.Outcome;
import com.fcourt.notification.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles one event: plan the email, claim the event in the database, then send. The claim and the
 * send share a transaction, so if the mail server refuses the message the claim disappears and the
 * Kafka retry sends it again. Delivery is therefore at-least-once: in the rare case the mail went out
 * but the commit failed, the recipient gets it twice.
 *
 * <p>Logs carry event id, type and outcome only: never the recipient, the body or a reset token.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectMapper json;
    private final EmailPlanner planner;
    private final ProcessedEventRepository processed;
    private final EmailSender sender;

    NotificationService(ObjectMapper json, EmailPlanner planner, ProcessedEventRepository processed,
                        EmailSender sender) {
        this.json = json;
        this.planner = planner;
        this.processed = processed;
        this.sender = sender;
    }

    @Transactional
    public void handle(String payload) {
        EventEnvelope event = EventEnvelope.parse(json, payload);
        switch (planner.plan(event)) {
            case EmailPlan.Ignore ignored -> log.debug("Ignoring event {} of type {}", event.eventId(), event.type());
            case EmailPlan.Expired expired -> log.info("Event {} of type {} expired before it was handled; no email sent",
                    event.eventId(), event.type());
            case EmailPlan.NoRecipient none -> {
                if (processed.claim(event.eventId(), event.type(), none.businessKey(), Outcome.SKIPPED_NO_RECIPIENT)) {
                    log.warn("Event {} of type {} has no usable recipient; no email sent", event.eventId(),
                            event.type());
                }
            }
            case EmailPlan.Send send -> {
                if (processed.claim(event.eventId(), event.type(), send.businessKey(), Outcome.SENT)) {
                    sender.send(send.email());
                    log.info("Sent {} email for event {}", send.email().template(), event.eventId());
                } else {
                    log.info("Event {} of type {} already handled; skipped", event.eventId(), event.type());
                }
            }
        }
    }
}
