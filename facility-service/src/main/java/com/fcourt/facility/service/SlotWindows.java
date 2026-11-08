package com.fcourt.facility.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Interval maths between 30-minute template slots (a local time range on one date) and lock windows
 * [from, to). A slot ending at 00:00 ends at midnight of the next day (the 23:30-00:00 slot), and a
 * lock window may cross midnight or span several days.
 */
public final class SlotWindows {
    private SlotWindows() { }

    public static LocalDateTime slotEnd(LocalDate date, LocalTime start, LocalTime end) {
        return end.equals(LocalTime.MIDNIGHT) && !start.equals(LocalTime.MIDNIGHT)
                ? date.plusDays(1).atTime(end) : date.atTime(end);
    }

    /** True when the slot and the half-open window [from, to) share any time. */
    public static boolean overlaps(LocalDate date, LocalTime start, LocalTime end, LocalDateTime from, LocalDateTime to) {
        return date.atTime(start).isBefore(to) && from.isBefore(slotEnd(date, start, end));
    }

    /** Every calendar date that [from, to) touches; a window ending exactly at 00:00 does not touch that day. */
    public static List<LocalDate> dates(LocalDateTime from, LocalDateTime to) {
        LocalDate last = to.toLocalTime().equals(LocalTime.MIDNIGHT) ? to.toLocalDate().minusDays(1) : to.toLocalDate();
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate day = from.toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) dates.add(day);
        return dates;
    }
}
