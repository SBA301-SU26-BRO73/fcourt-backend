package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
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

    @Test
    void acceptsLastSlotOfDayEndingAtMidnight() {
        var date = LocalDate.of(2030, 1, 1);
        var slot = new BookingGateway.Session(42, date, LocalTime.of(23, 30), LocalTime.MIDNIGHT, "CONFIRMED", null, null);
        assertThatCode(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7,
                List.of(new BookingGateway.Court(1, "Court", List.of(slot)))), 7, date, date))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledBookingDoesNotBlockRebookingOfTheSameSlot() {
        var date = LocalDate.of(2030, 1, 1);
        var cancelled = new BookingGateway.Session(41, date, LocalTime.of(9, 0), LocalTime.of(9, 30), "CANCELLED", null, null);
        var active = new BookingGateway.Session(42, date, LocalTime.of(9, 0), LocalTime.of(9, 30), "CONFIRMED", null, null);
        assertThatCode(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7,
                List.of(new BookingGateway.Court(1, "Court", List.of(cancelled, active)))), 7, date, date))
                .doesNotThrowAnyException();
        var second = new BookingGateway.Session(43, date, LocalTime.of(9, 0), LocalTime.of(9, 30), "CHECKED_IN", null, null);
        assertThatThrownBy(() -> CheckInService.validateSchedule(new BookingGateway.Schedule(7,
                List.of(new BookingGateway.Court(1, "Court", List.of(active, second)))), 7, date, date))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void checkInOfAnEarlierBusinessDateIsRejectedAndNotRecorded() {
        var staff = mock(StaffService.class);
        var booking = mock(BookingGateway.class);
        var commands = mock(BookingCommandStore.class);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("100").build();
        when(staff.assigned(jwt)).thenReturn(new StaffView(1, 100, 7, null, null, null, true,
                Instant.EPOCH, Instant.EPOCH, List.of()));
        var now = Instant.parse("2030-01-02T03:00:00Z");
        var yesterday = Instant.parse("2030-01-01T03:00:00Z");
        when(booking.checkIn(any(), any(), any())).thenReturn(new BookingGateway.CheckInResult(
                5, 7, LocalDate.of(2030, 1, 1), "CHECKED_IN", yesterday));
        var service = new CheckInService(staff, booking, commands, Clock.fixed(now, ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThatThrownBy(() -> service.verify(jwt, java.util.UUID.randomUUID(), "FC-1", "QR"))
                .isInstanceOf(ResponseStatusException.class);
        verify(commands, never()).complete(anyLong(), any(), any(), any(), any(), anyLong(), any());
    }
}
