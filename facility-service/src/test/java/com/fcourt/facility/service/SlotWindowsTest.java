package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SlotWindowsTest {
    static final LocalDate DAY = LocalDate.of(2030, 1, 5);

    static LocalDateTime at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time));
    }

    static boolean overlaps(String slotStart, String slotEnd, LocalDateTime from, LocalDateTime to) {
        return SlotWindows.overlaps(DAY, LocalTime.parse(slotStart), LocalTime.parse(slotEnd), from, to);
    }

    @Test
    void touchingBoundariesDoNotOverlap() {
        var from = at(DAY, "13:00");
        var to = at(DAY, "18:00");
        assertThat(overlaps("12:30", "13:00", from, to)).isFalse();
        assertThat(overlaps("13:00", "13:30", from, to)).isTrue();
        assertThat(overlaps("17:30", "18:00", from, to)).isTrue();
        assertThat(overlaps("18:00", "18:30", from, to)).isFalse();
    }

    @Test
    void windowInsideOneSlotOverlapsIt() {
        assertThat(overlaps("13:00", "13:30", at(DAY, "13:10"), at(DAY, "13:20"))).isTrue();
    }

    @Test
    void windowCrossingMidnightCoversTheLastSlotOfTheDayAndTheFirstOfTheNext() {
        var from = at(DAY, "22:00");
        var to = at(DAY.plusDays(1), "02:00");
        // 23:30-00:00 is stored with endTime 00:00 and ends at midnight of the next day.
        assertThat(overlaps("23:30", "00:00", from, to)).isTrue();
        assertThat(SlotWindows.overlaps(DAY.plusDays(1), LocalTime.of(0, 0), LocalTime.of(0, 30), from, to)).isTrue();
        assertThat(SlotWindows.overlaps(DAY.plusDays(1), LocalTime.of(1, 30), LocalTime.of(2, 0), from, to)).isTrue();
        assertThat(SlotWindows.overlaps(DAY.plusDays(1), LocalTime.of(2, 0), LocalTime.of(2, 30), from, to)).isFalse();
        assertThat(overlaps("21:30", "22:00", from, to)).isFalse();
    }

    @Test
    void lastSlotOfTheDayEndsAtNextMidnight() {
        assertThat(SlotWindows.slotEnd(DAY, LocalTime.of(23, 30), LocalTime.MIDNIGHT)).isEqualTo(at(DAY.plusDays(1), "00:00"));
        // A window that starts exactly at midnight does not touch the previous day's 23:30-00:00 slot.
        assertThat(overlaps("23:30", "00:00", at(DAY.plusDays(1), "00:00"), at(DAY.plusDays(1), "06:00"))).isFalse();
    }

    @Test
    void multiDayWindowTouchesEveryDate() {
        assertThat(SlotWindows.dates(at(DAY, "18:00"), at(DAY.plusDays(2), "09:00")))
                .containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
    }

    @Test
    void windowEndingAtMidnightDoesNotTouchTheNextDate() {
        assertThat(SlotWindows.dates(at(DAY, "00:00"), at(DAY.plusDays(1), "00:00"))).containsExactly(DAY);
        assertThat(SlotWindows.dates(at(DAY, "22:00"), at(DAY.plusDays(1), "00:30")))
                .containsExactly(DAY, DAY.plusDays(1));
    }
}
