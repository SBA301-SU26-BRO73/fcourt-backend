package com.fcourt.notification.event;

import com.fcourt.notification.mail.OutgoingEmail;

/** What to do with one event. */
public sealed interface EmailPlan {
    /** Not an event this service sends mail for. */
    record Ignore() implements EmailPlan {
    }

    /**
     * A mail would be useless by now, for example a password reset link that already expired because the
     * event was consumed late (outage, new deployment reading from the earliest offset).
     */
    record Expired() implements EmailPlan {
    }

    /**
     * A mail is due but the event carries no usable recipient address. It deliberately has no business key:
     * a skipped event must not use up the key, so a later event about the same booking that does carry a
     * recipient is still mailed.
     */
    record NoRecipient() implements EmailPlan {
    }

    /**
     * {@code businessKey} (nullable) identifies the real-world fact, so two events about the same
     * booking cannot produce two emails.
     */
    record Send(OutgoingEmail email, String businessKey) implements EmailPlan {
    }
}
