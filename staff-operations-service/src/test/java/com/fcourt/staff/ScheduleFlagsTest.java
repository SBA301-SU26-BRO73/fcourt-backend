package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.staff.integration.BookingGateway.Session;
import com.fcourt.staff.service.ScheduleFlags;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleFlagsTest {
    final ScheduleFlags flags = new ScheduleFlags(ZoneId.of("Asia/Ho_Chi_Minh"), 10);

    @ParameterizedTest
    @CsvSource({"2030-01-01T02:14:59Z,false", "2030-01-01T02:15:00Z,false", "2030-01-01T02:15:01Z,true"})
    void lateCheckInIsStrictlyAfterFifteenMinutes(String time, boolean expected) {
        assertThat(flags.forSession(slot("CONFIRMED", "09:00", "09:30"), Instant.parse(time)).lateCheckIn()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"2030-01-01T02:19:59Z,false", "2030-01-01T02:20:00Z,true", "2030-01-01T02:29:59Z,true", "2030-01-01T02:30:00Z,false"})
    void endingSoonUsesConfiguredInclusiveStartExclusiveEnd(String time, boolean expected) {
        assertThat(flags.forSession(slot("CHECKED_IN", "09:00", "09:30"), Instant.parse(time)).endingSoon()).isEqualTo(expected);
    }

    @Test
    void completedAndCancelledAreNeverPlayingOrLate() {
        for (String status : new String[]{"COMPLETED", "CANCELLED", "PENDING_PAYMENT"}) {
            var result = flags.forSession(slot(status, "09:00", "09:30"), Instant.parse("2030-01-01T02:25:00Z"));
            assertThat(result.playing()).isFalse();
            assertThat(result.endingSoon()).isFalse();
            assertThat(result.lateCheckIn()).isFalse();
        }
    }

    @Test
    void midnightEndUsesFollowingBusinessDayAndDifferentThresholdIsConfigurable() {
        assertThat(flags.forSession(slot("CHECKED_IN", "23:30", "00:00"), Instant.parse("2030-01-01T16:55:00Z")).endingSoon()).isTrue();
        var fiveMinutes = new ScheduleFlags(ZoneId.of("Asia/Ho_Chi_Minh"), 5);
        assertThat(fiveMinutes.forSession(slot("CHECKED_IN", "09:00", "09:30"), Instant.parse("2030-01-01T02:24:59Z")).endingSoon()).isFalse();
    }

    Session slot(String status, String start, String end) {
        return new Session(42, LocalDate.of(2030, 1, 1), LocalTime.parse(start), LocalTime.parse(end), status, "Guest", null);
    }
}
