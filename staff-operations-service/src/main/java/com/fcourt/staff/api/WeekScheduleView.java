package com.fcourt.staff.api;

import com.fcourt.staff.service.ScheduleFlags.Flags;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record WeekScheduleView(long branchId, LocalDate startDate, LocalDate endDate, Instant generatedAt, List<Court> courts) {
    public record Court(long courtId, String courtName, List<Day> days) { }
    public record Day(LocalDate date, List<Entry> slots) { }
    public record Entry(long bookingId, LocalTime slotStart, LocalTime slotEnd, String status,
                        String customerName, String guestPhone, Flags flags) { }
}
