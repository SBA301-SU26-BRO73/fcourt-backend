package com.fcourt.notification.reminder;

import java.time.Instant;

public interface ReminderTrigger {
    void create(long bookingId, Instant fireAt);
    void delete(long bookingId);
}
