package com.fcourt.staff.service;

import com.fcourt.staff.api.WeekScheduleView;
import com.fcourt.staff.integration.BookingGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WeekScheduleService {
    private final StaffService staff;
    private final BookingGateway booking;
    private final ScheduleFlags flags;
    private final Clock clock;

    public WeekScheduleService(StaffService staff, BookingGateway booking, ScheduleFlags flags, Clock clock) {
        this.staff = staff; this.booking = booking; this.flags = flags; this.clock = clock;
    }

    public WeekScheduleView get(Jwt jwt, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 7 || start.getYear() < 1 || end.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Schedule range must be 1 to 7 supported calendar days");
        }
        var assignment = staff.assigned(jwt);
        var schedule = booking.schedule(assignment.branchId(), start, end, jwt.getTokenValue());
        CheckInService.validateSchedule(schedule, assignment.branchId(), start, end);
        var now = clock.instant();
        var dates = start.datesUntil(end.plusDays(1)).toList();
        List<WeekScheduleView.Court> courts = schedule.courts().stream().sorted(Comparator.comparingLong(BookingGateway.Court::courtId)).map(court -> {
            var byDate = court.slots().stream().collect(Collectors.groupingBy(BookingGateway.Session::date));
            var dayViews = dates.stream().map(date -> new WeekScheduleView.Day(date,
                    byDate.getOrDefault(date, List.of()).stream().sorted(Comparator.comparing(BookingGateway.Session::slotStart))
                            .map(slot -> new WeekScheduleView.Entry(slot.bookingId(), slot.slotStart(), slot.slotEnd(), slot.status(),
                                    slot.customerName(), slot.guestPhone(), flags.forSession(slot, now))).toList())).toList();
            return new WeekScheduleView.Court(court.courtId(), court.courtName(), dayViews);
        }).toList();
        return new WeekScheduleView(assignment.branchId(), start, end, now, courts);
    }
}
