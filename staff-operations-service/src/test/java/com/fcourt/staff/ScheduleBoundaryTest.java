package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.staff.api.StaffView;
import com.fcourt.staff.integration.BookingGateway;
import com.fcourt.staff.persistence.BookingCommandStore;
import com.fcourt.staff.service.CheckInService;
import com.fcourt.staff.service.StaffService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class ScheduleBoundaryTest {
    @Test
    void todayUsesBusinessDateAcrossUtcMidnightBoundary() {
        var staff = mock(StaffService.class);
        var booking = mock(BookingGateway.class);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("100").build();
        when(staff.assigned(jwt)).thenReturn(new StaffView(1, 100, 7, null, null, null, true,
                Instant.EPOCH, Instant.EPOCH, List.of()));
        LocalDate today = LocalDate.of(2030, 1, 2);
        when(booking.schedule(7, today, today, "test")).thenReturn(new BookingGateway.Schedule(7, List.of()));
        var service = new CheckInService(staff, booking, mock(BookingCommandStore.class),
                Clock.fixed(Instant.parse("2030-01-01T18:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh"));
        service.today(jwt);
        verify(booking).schedule(7, today, today, "test");
    }

    @Test
    void rejectsDuplicateCourtAndSlotIdentity() {
        var date = LocalDate.of(2030, 1, 1);
        var slot = new BookingGateway.Session(42, date, LocalTime.of(9, 0), LocalTime.of(9, 30), "CONFIRMED", null, null);
        var court = new BookingGateway.Court(1, "Court", List.of(slot));
        assertThatThrownBy(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7, List.of(court, court)), 7, date, date))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7,
                List.of(new BookingGateway.Court(1, "Court", List.of(slot, slot)))), 7, date, date))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsInvalidTimeAndUnknownBookingState() {
        var date = LocalDate.of(2030, 1, 1);
        for (var slot : List.of(
                new BookingGateway.Session(42, date, LocalTime.of(9, 30), LocalTime.of(9, 0), "CONFIRMED", null, null),
                new BookingGateway.Session(42, date, LocalTime.of(9, 0), LocalTime.of(9, 30), "UNKNOWN", null, null),
                new BookingGateway.Session(42, date.plusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 30), "CONFIRMED", null, null))) {
            assertThatThrownBy(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7,
                    List.of(new BookingGateway.Court(1, "Court", List.of(slot)))), 7, date, date))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
