package com.fcourt.notification.reminder;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReminderProjectionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReminderProjection projection = new ReminderProjection(jdbc);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void confirmedBookingProjectsFirstSlotWithRecipientAndTicket() {
        projection.apply(event("""
                {"type":"PaymentConfirmedEvent","recipient":{"email":"player@example.com","fullName":"Player"},
                 "payment":{"bookingId":42,"customerId":7,"checkInCode":"TICKET-42","checkout":{
                   "date":"2026-11-08","bookingCode":"BK-42","branchName":"District 1",
                   "courtName":"Court A","slots":[{"startTime":"20:00"},{"startTime":"18:00"}]}}}
                """));

        verify(jdbc).update(contains("INSERT INTO booking_reminders"), eq(42L), eq("player@example.com"),
                eq("Player"), eq("BK-42"), eq("District 1"), eq("Court A"),
                eq(Timestamp.from(Instant.parse("2026-11-08T11:00:00Z"))), eq("TICKET-42"), eq(7L), eq(42L));
    }

    @Test
    void missingEmailStillProjectsPushReminderWithoutEmailRecipient() {
        projection.apply(event("""
                {"type":"PaymentConfirmedEvent","payment":{"bookingId":42,"checkInCode":"TICKET","checkout":{
                  "date":"2026-11-08","slots":[{"startTime":"18:00"}]}}}
                """));
        verify(jdbc).update(contains("INSERT INTO booking_reminders"), eq(42L), isNull(), anyString(),
                anyString(), anyString(), anyString(), any(Timestamp.class), eq("TICKET"), isNull(), eq(42L));
    }

    @Test
    void cancellationLeavesTombstoneAndRemovesPendingReminder() {
        projection.apply(event("""
                {"type":"BookingCancelledEvent","booking":{"bookingId":42}}
                """));
        verify(jdbc).update(contains("INSERT INTO cancelled_booking_reminders"), eq(42L));
        verify(jdbc).update(contains("DELETE FROM booking_reminders"), eq(42L));
    }

    private EventEnvelope event(String payload) {
        return EventEnvelope.parse(json,
                payload.replace("{\"type\"", "{\"eventId\":\"00000000-0000-0000-0000-000000000042\",\"type\""));
    }
}
