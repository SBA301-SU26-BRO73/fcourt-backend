package com.fcourt.notification.event;

import com.fcourt.notification.mail.OutgoingEmail;

/** What to do with one event. */
public sealed interface EmailPlan {
    /** Not an event this service sends mail for. */
    record Ignore() implements EmailPlan {
    }

    /** A mail is due but the event carries no usable recipient address. */
    record NoRecipient(String businessKey) implements EmailPlan {
    }

    /**
     * {@code businessKey} (nullable) identifies the real-world fact, so two events about the same
     * booking cannot produce two emails.
     */
    record Send(OutgoingEmail email, String businessKey) implements EmailPlan {
    }
}
