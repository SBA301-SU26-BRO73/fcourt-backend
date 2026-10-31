package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.staff.api.StaffRequests.Shift;
import com.fcourt.staff.service.StaffService;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

class ShiftValidationTest {
    @Test
    void adjacentShiftsAndDifferentDaysAreAllowed() {
        assertThatCode(() -> StaffService.validateShifts(List.of(
                shift(1, "12:00", "18:00"), shift(1, "09:00", "12:00"), shift(2, "09:00", "12:00"))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"09:00,09:00", "22:00,06:00", "09:00:01,10:00", "09:00,10:00:01", "09:00:00.1,10:00"})
    void rejectsZeroLengthOvernightAndSubMinuteShifts(String start, String end) {
        assertThatThrownBy(() -> StaffService.validateShifts(List.of(shift(1, start, end))))
                .isInstanceOf(ResponseStatusException.class);
    }

    static Shift shift(int day, String start, String end) {
        return new Shift(day, LocalTime.parse(start), LocalTime.parse(end));
    }
}
