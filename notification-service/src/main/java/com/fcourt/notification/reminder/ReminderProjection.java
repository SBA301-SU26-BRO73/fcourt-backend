package com.fcourt.notification.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.event.PoisonEventException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import jakarta.mail.internet.InternetAddress;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A notification-owned read model populated from confirmed/cancelled booking events. */
@Component
public class ReminderProjection {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;

    public ReminderProjection(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case "PaymentConfirmedEvent" -> confirmed(event);
            case "PaymentRejectedEvent", "BookingCancelledEvent" -> cancelled(event);
            default -> { }
        }
    }

    private void confirmed(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long id = bookingId(payment.path("bookingId"));
        JsonNode recipient = event.root().path("recipient");
        String email = recipient.path("email").asText("").strip();
        if (!validAddress(email)) return;
        JsonNode checkout = payment.path("checkout");
        JsonNode slots = checkout.path("slots");
        if (!slots.isArray() || slots.isEmpty()) throw new PoisonEventException("Confirmed booking has no slots");
        try {
            var date = LocalDate.parse(checkout.path("date").asText());
            LocalTime first = null;
            for (JsonNode slot : slots) {
                LocalTime start = LocalTime.parse(slot.path("startTime").asText());
                if (first == null || start.isBefore(first)) first = start;
            }
            String ticket = payment.path("checkInCode").asText("");
            if (ticket.isBlank()) throw new PoisonEventException("Confirmed booking has no check-in code");
            jdbc.update("""
                    INSERT INTO booking_reminders(booking_id, recipient_email, recipient_name, booking_code,
                        branch_name, court_name, starts_at, ticket_code)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM cancelled_booking_reminders WHERE booking_id = ?)
                    ON CONFLICT (booking_id) DO NOTHING
                    """, id, email, recipient.path("fullName").asText("Quý khách"),
                    checkout.path("bookingCode").asText("#" + id),
                    checkout.path("branchName").asText(""), checkout.path("courtName").asText(""),
                    java.sql.Timestamp.from(date.atTime(first).atZone(VIETNAM).toInstant()), ticket, id);
        } catch (java.time.DateTimeException error) {
            throw new PoisonEventException("Confirmed booking has malformed date or time");
        }
    }

    private void cancelled(EventEnvelope event) {
        JsonNode source = event.root().path("booking");
        if (!source.isObject()) source = event.root().path("payment");
        if (source.isObject() && source.path("bookingId").canConvertToLong()) {
            jdbc.update("INSERT INTO cancelled_booking_reminders(booking_id) VALUES (?) ON CONFLICT DO NOTHING",
                    source.path("bookingId").asLong());
            jdbc.update("DELETE FROM booking_reminders WHERE booking_id = ? AND reminder_sent = false",
                    source.path("bookingId").asLong());
        }
    }

    private boolean validAddress(String email) {
        try {
            var address = new InternetAddress(email, true);
            address.validate();
            return email.equals(address.getAddress()) && email.contains("@");
        } catch (jakarta.mail.internet.AddressException error) {
            return false;
        }
    }

    private long bookingId(JsonNode node) {
        if (!node.canConvertToLong() || node.asLong() <= 0)
            throw new PoisonEventException("Confirmed booking has no valid bookingId");
        return node.asLong();
    }
}
