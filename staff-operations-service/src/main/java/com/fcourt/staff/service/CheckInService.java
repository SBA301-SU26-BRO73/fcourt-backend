package com.fcourt.staff.service;

import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.integration.BookingGateway.CheckInResult;
import com.fcourt.staff.persistence.BookingCommandStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInService {
    private final StaffService staff;
    private final BookingGateway booking;
    private final BookingCommandStore commands;
    private final Clock clock;
    private final ZoneId zone;

    public CheckInService(StaffService staff, BookingGateway booking, BookingCommandStore commands, Clock clock, ZoneId zone) {
        this.staff = staff; this.booking = booking; this.commands = commands; this.clock = clock; this.zone = zone;
    }

    public BookingGateway.Schedule today(Jwt jwt) {
        var assignment = staff.assigned(jwt);
        LocalDate date = LocalDate.now(clock.withZone(zone));
        var schedule = booking.schedule(assignment.branchId(), date, date, jwt.getTokenValue());
        validateSchedule(schedule, assignment.branchId(), date, date);
        return schedule;
    }

    @Transactional(timeout = 20)
    public CheckInResult verify(Jwt jwt, UUID key, String code, String channel) {
        var assignment = staff.assigned(jwt);
        long actor = Long.parseLong(jwt.getSubject());
        LocalDate date = LocalDate.now(clock.withZone(zone));
        String normalized = code.strip();
        String fingerprint = commands.fingerprint(List.of(assignment.branchId(), normalized, channel));
        var replay = commands.replay(actor, key, "BookingCheckedInEvent", fingerprint, CheckInResult.class);
        if (replay != null) return replay;
        var result = booking.checkIn(new BookingGateway.CheckInCommand(assignment.branchId(), actor, normalized,
                date, clock.instant(), channel), "staff:check-in:" + actor + ":" + key, jwt.getTokenValue());
        if (result.bookingId() <= 0 || result.branchId() != assignment.branchId() || result.date() == null
                || result.date().isAfter(date) || !"CHECKED_IN".equals(result.status()) || result.checkedInAt() == null
                || !result.checkedInAt().atZone(zone).toLocalDate().equals(result.date())) throw BookingGateway.unavailable();
        commands.auditCheckIn(actor, key, result.bookingId(), assignment.branchId(), channel, result.checkedInAt());
        commands.complete(actor, key, "BookingCheckedInEvent", fingerprint, result, result.bookingId(),
                Map.of("booking", result, "actorId", actor, "channel", channel, "occurredAt", result.checkedInAt()));
        return result;
    }

    public static void validateSchedule(BookingGateway.Schedule schedule, long branch, LocalDate start, LocalDate end) {
        if (schedule.branchId() != branch || schedule.courts() == null || schedule.courts().size() > 100) throw BookingGateway.unavailable();
        Set<Long> courts = new HashSet<>();
        Set<String> slots = new HashSet<>();
        for (var court : schedule.courts()) {
            if (court == null || court.courtId() <= 0 || court.courtName() == null || !courts.add(court.courtId())
                    || court.slots() == null || court.slots().size() > 3360) throw BookingGateway.unavailable();
            for (var slot : court.slots()) {
                if (slot == null || slot.bookingId() <= 0 || slot.date() == null || slot.date().isBefore(start)
                        || slot.date().isAfter(end) || slot.slotStart() == null || slot.slotEnd() == null
                        || (!slot.slotEnd().isAfter(slot.slotStart()) && !slot.slotEnd().equals(java.time.LocalTime.MIDNIGHT))
                        || slot.slotEnd().equals(slot.slotStart()) || slot.status() == null
                        || !Set.of("PENDING_PAYMENT", "AWAITING_CONFIRMATION", "CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED").contains(slot.status())
                        || !slots.add(court.courtId() + ":" + slot.date() + ":" + slot.slotStart())) throw BookingGateway.unavailable();
            }
        }
    }
}
