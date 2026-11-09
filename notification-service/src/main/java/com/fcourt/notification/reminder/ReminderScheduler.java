package com.fcourt.notification.reminder;

import com.fcourt.notification.mail.EmailSender;
import com.fcourt.notification.mail.OutgoingEmail;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Finds confirmed bookings about two hours from play and sends one reminder per booking. */
@Component
@ConditionalOnProperty(name = "notification.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {
    private final JdbcTemplate jdbc;
    private final ReminderDelivery delivery;
    private final Clock clock;

    ReminderScheduler(JdbcTemplate jdbc, ReminderDelivery delivery, Clock clock) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.reminder.delay-ms:900000}")
    public void sendDue() {
        Instant now = clock.instant();
        var ids = jdbc.queryForList("""
                SELECT booking_id FROM booking_reminders
                WHERE reminder_sent = false AND recipient_email IS NOT NULL AND starts_at > ? AND starts_at <= ?
                ORDER BY starts_at LIMIT 100
                """, Long.class, Timestamp.from(now),
                Timestamp.from(now.plus(Duration.ofMinutes(135))));
        for (Long id : ids) delivery.send(id, now);
    }

    @Component
    static class ReminderDelivery {
        private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
        private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
        private final JdbcTemplate jdbc;
        private final EmailSender sender;

        ReminderDelivery(JdbcTemplate jdbc, EmailSender sender) {
            this.jdbc = jdbc;
            this.sender = sender;
        }

        @Transactional
        public void send(long id, Instant now) {
            var rows = jdbc.query("""
                    SELECT * FROM booking_reminders WHERE booking_id = ? AND reminder_sent = false
                    FOR UPDATE SKIP LOCKED
                    """, (row, index) -> new Reminder(row.getLong("booking_id"),
                    row.getString("recipient_email"), row.getString("recipient_name"),
                    row.getString("booking_code"), row.getString("branch_name"),
                    row.getString("court_name"), row.getTimestamp("starts_at").toInstant(),
                    row.getString("ticket_code")), id);
            if (rows.isEmpty()) return;
            Reminder reminder = rows.getFirst();
            if (!reminder.startsAt().isAfter(now)
                    || reminder.startsAt().isAfter(now.plus(Duration.ofMinutes(135)))) return;
            String map = "https://www.google.com/maps/search/?api=1&query="
                    + URLEncoder.encode(reminder.branchName(), StandardCharsets.UTF_8);
            sender.send(new OutgoingEmail(reminder.email(), "Nhắc lịch chơi sân " + reminder.bookingCode(),
                    "booking-reminder", Map.of(
                    "name", reminder.name(), "bookingCode", reminder.bookingCode(),
                    "when", WHEN.format(reminder.startsAt().atZone(VIETNAM)),
                    "branchName", reminder.branchName(), "courtName", reminder.courtName(),
                    "mapUrl", map, "ticketCode", reminder.ticketCode()), reminder.ticketCode()));
            jdbc.update("UPDATE booking_reminders SET reminder_sent = true, sent_at = ? WHERE booking_id = ?",
                    Timestamp.from(now), id);
        }

        record Reminder(long id, String email, String name, String bookingCode, String branchName,
                        String courtName, Instant startsAt, String ticketCode) { }
    }
}
