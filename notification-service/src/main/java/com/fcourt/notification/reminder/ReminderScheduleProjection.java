package com.fcourt.notification.reminder;

import com.fcourt.notification.event.EventEnvelope;
import java.time.Duration;
import java.sql.Timestamp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReminderScheduleProjection {
    private final JdbcTemplate jdbc;
    private final ReminderTrigger trigger;
    public ReminderScheduleProjection(JdbcTemplate jdbc, ReminderTrigger trigger) { this.jdbc=jdbc; this.trigger=trigger; }
    public void apply(EventEnvelope event) {
        if ("PaymentConfirmedEvent".equals(event.type())) {
            long id=event.root().path("payment").path("bookingId").asLong();
            try {
                Timestamp startsAt = jdbc.queryForObject("SELECT starts_at FROM booking_reminders WHERE booking_id=?",
                        Timestamp.class, id);
                if (startsAt != null) trigger.create(id, startsAt.toInstant().minus(Duration.ofHours(2)));
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) { }
        } else if ("PaymentRejectedEvent".equals(event.type()) || "BookingCancelledEvent".equals(event.type())) {
            var b=event.root().path("booking").isObject()?event.root().path("booking"):event.root().path("payment");
            if (b.path("bookingId").canConvertToLong()) trigger.delete(b.path("bookingId").asLong());
        }
    }
}
