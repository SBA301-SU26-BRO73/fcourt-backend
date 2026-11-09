package com.fcourt.notification.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EmailPlan;
import com.fcourt.notification.event.EmailPlanner;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.inbox.ProcessedEventRepository.Outcome;
import com.fcourt.notification.mail.EmailSender;
import com.fcourt.notification.reminder.ReminderProjection;
import com.fcourt.notification.reminder.ReminderScheduleProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits email handling independently so a later push retry cannot resend an already-sent email. */
@Service
public class EmailEventHandler {
    private static final Logger log = LoggerFactory.getLogger(EmailEventHandler.class);
    private final ObjectMapper json;
    private final EmailPlanner planner;
    private final ProcessedEventRepository processed;
    private final EmailSender sender;
    private final ReminderProjection reminders;
    private final ReminderScheduleProjection schedules;

    EmailEventHandler(ObjectMapper json, EmailPlanner planner, ProcessedEventRepository processed,
                      EmailSender sender, ReminderProjection reminders, ReminderScheduleProjection schedules) {
        this.json = json; this.planner = planner; this.processed = processed; this.sender = sender;
        this.reminders = reminders;
        this.schedules = schedules;
    }

    @Transactional
    public EventEnvelope handle(String payload) {
        EventEnvelope event = EventEnvelope.parse(json, payload);
        reminders.apply(event);
        // Push schedules use their own sent flag and do not replace the existing email ReminderScheduler.
        schedules.apply(event);
        switch (planner.plan(event)) {
            case EmailPlan.Ignore ignored -> log.debug("Ignoring event {} of type {}", event.eventId(), event.type());
            case EmailPlan.Expired expired -> log.info("Event {} expired before it was handled; no email sent", event.eventId());
            case EmailPlan.NoRecipient none -> processed.claim(event.eventId(), event.type(), null, Outcome.SKIPPED_NO_RECIPIENT);
            case EmailPlan.Send send -> {
                if (processed.claim(event.eventId(), event.type(), send.businessKey(), Outcome.SENT)) {
                    sender.send(send.email());
                    log.info("Sent {} email for event {}", send.email().template(), event.eventId());
                }
            }
        }
        return event;
    }
}
