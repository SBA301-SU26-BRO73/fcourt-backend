package com.fcourt.staff.service;

import com.fcourt.staff.integration.BookingGateway.Session;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScheduleFlags {
    private final ZoneId zone;
    private final int endingMinutes;

    public ScheduleFlags(ZoneId zone, @Value("${staff.schedule.ending-soon-minutes:10}") int endingMinutes) {
        if (endingMinutes < 1 || endingMinutes > 60) throw new IllegalArgumentException("Invalid ending-soon threshold");
        this.zone = zone; this.endingMinutes = endingMinutes;
    }

    public Flags forSession(Session slot, Instant now) {
        Instant start = slot.date().atTime(slot.slotStart()).atZone(zone).toInstant();
        var endDate = slot.slotEnd().isAfter(slot.slotStart()) ? slot.date() : slot.date().plusDays(1);
        Instant end = endDate.atTime(slot.slotEnd()).atZone(zone).toInstant();
        boolean playing = "CHECKED_IN".equals(slot.status());
        boolean late = "CONFIRMED".equals(slot.status()) && now.isAfter(start.plusSeconds(15 * 60));
        boolean ending = playing && !now.isBefore(end.minusSeconds(endingMinutes * 60L)) && now.isBefore(end);
        return new Flags(late, playing, ending);
    }

    public record Flags(boolean lateCheckIn, boolean playing, boolean endingSoon) { }
}
