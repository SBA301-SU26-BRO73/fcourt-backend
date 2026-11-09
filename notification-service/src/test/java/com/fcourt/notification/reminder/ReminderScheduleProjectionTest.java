package com.fcourt.notification.reminder;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReminderScheduleProjectionTest {
    static class FakeTrigger implements ReminderTrigger {
        final ArrayList<String> calls = new ArrayList<>();
        Instant at;
        public void create(long id, Instant fireAt) { calls.add("create:" + id); at=fireAt; }
        public void delete(long id) { calls.add("delete:" + id); }
    }
    private EventEnvelope event(String type, String body) {
        return EventEnvelope.parse(new ObjectMapper(), """
                {"eventId":"%s","type":"%s",%s}
                """.formatted(UUID.randomUUID(), type, body));
    }
    @Test void createsReminderTwoHoursBeforeTheProjectedBookingStart() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); FakeTrigger trigger=new FakeTrigger();
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), eq(42L)))
                .thenReturn(Timestamp.from(Instant.parse("2026-11-09T12:00:00Z")));
        new ReminderScheduleProjection(jdbc, trigger).apply(event("PaymentConfirmedEvent", "\"payment\":{\"bookingId\":42}"));
        verify(jdbc).queryForObject(contains("booking_reminders"), eq(Timestamp.class), eq(42L));
        org.assertj.core.api.Assertions.assertThat(trigger.at).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    }
    @Test void deletesScheduleForCancellationAndRejection() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); FakeTrigger trigger=new FakeTrigger();
        var projection=new ReminderScheduleProjection(jdbc, trigger);
        projection.apply(event("BookingCancelledEvent", "\"booking\":{\"bookingId\":42}"));
        projection.apply(event("PaymentRejectedEvent", "\"payment\":{\"bookingId\":43}"));
        org.assertj.core.api.Assertions.assertThat(trigger.calls).containsExactly("delete:42", "delete:43");
    }
}
