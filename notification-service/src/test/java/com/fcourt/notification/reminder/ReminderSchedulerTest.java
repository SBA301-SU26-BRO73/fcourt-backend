package com.fcourt.notification.reminder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReminderSchedulerTest {
    @Test
    void scansFromNowToTwoHoursAheadAndDeliversDueBookings() {
        var jdbc = mock(JdbcTemplate.class);
        var delivery = mock(ReminderScheduler.ReminderDelivery.class);
        var now = Instant.parse("2026-11-07T09:00:00Z");
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(Timestamp.from(now)),
                eq(Timestamp.from(now.plusSeconds(135 * 60))))).thenReturn(List.of(42L, 43L));

        new ReminderScheduler(jdbc, delivery, Clock.fixed(now, ZoneOffset.UTC)).sendDue();

        verify(delivery).send(42L, now);
        verify(delivery).send(43L, now);
    }
}
