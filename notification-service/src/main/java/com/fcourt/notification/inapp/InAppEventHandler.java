package com.fcourt.notification.inapp;

import com.fcourt.notification.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the in-app rows of one event in one transaction, after (and independently of) the email: it needs no
 * recipient address and does not care whether mail is sent. Idempotent through {@code (user_id, dedupe_key)}.
 */
@Service
public class InAppEventHandler {
    private static final Logger log = LoggerFactory.getLogger(InAppEventHandler.class);
    private final InAppPlanner planner;
    private final InAppNotificationRepository notifications;

    InAppEventHandler(InAppPlanner planner, InAppNotificationRepository notifications) {
        this.planner = planner;
        this.notifications = notifications;
    }

    @Transactional
    public void handle(EventEnvelope event) {
        for (InAppPlan plan : planner.plan(event)) {
            if (notifications.insert(plan)) {
                log.info("In-app {} notification created for event {}", plan.type(), event.eventId());
            }
        }
    }
}
