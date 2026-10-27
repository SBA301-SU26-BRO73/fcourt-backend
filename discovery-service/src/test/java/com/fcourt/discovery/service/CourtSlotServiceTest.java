package com.fcourt.discovery.service;

import com.fcourt.discovery.component.SlotSourceClient;
import com.fcourt.discovery.component.SlotSourceClient.*;
import com.fcourt.discovery.dto.response.CourtSlotResponse.Status;
import com.fcourt.discovery.repository.SlotHoldRepository;
import com.fcourt.discovery.service.impl.CourtSlotServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourtSlotServiceTest {
    private static final LocalDate DATE = LocalDate.of(2030, 1, 1);
    private final SlotSourceClient source = mock(SlotSourceClient.class);
    private final SlotHoldRepository holds = mock(SlotHoldRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T03:00:00Z"), ZoneOffset.UTC);
    private final CourtSlotService service = new CourtSlotServiceImpl(source, holds, clock);

    @BeforeEach
    void setup() {
        when(source.occupancy(1, DATE)).thenReturn(new Occupancy(1L, DATE, List.of()));
        when(holds.heldSlots(anyLong(), any(), anyList())).thenReturn(Set.of());
    }

    private Slot slot(long id, String time, boolean closed) {
        LocalTime start = LocalTime.parse(time);
        return new Slot(id, start, start.plusMinutes(30), new BigDecimal("75000.00"), closed);
    }

    private void schedule(LocalDate date, boolean closed, Slot... slots) {
        when(source.schedule(1, date)).thenReturn(new Schedule(1L, date, closed, Arrays.asList(slots)));
    }

    @Test
    void sortsAndResolvesEveryStatusWithoutLeakingHoldTokens() {
        schedule(DATE, false, slot(6, "12:00", false), slot(1, "09:30", true),
                slot(2, "10:00", false), slot(3, "10:30", true),
                slot(4, "11:00", false), slot(5, "11:30", false));
        when(source.occupancy(1, DATE)).thenReturn(new Occupancy(1L, DATE, List.of(1L, 2L, 3L, 4L)));
        when(holds.heldSlots(anyLong(), any(), anyList())).thenReturn(Set.of(1L, 2L, 3L, 4L, 5L));
        var result = service.find(1, DATE);
        assertThat(result).extracting(r -> r.slotId()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(result).extracting(r -> r.status()).containsExactly(Status.EXPIRED, Status.EXPIRED,
                Status.OUT_OF_SERVICE, Status.BOOKED, Status.HOLD, Status.AVAILABLE);
        assertThat(result).allSatisfy(r -> assertThat(r.price()).isEqualByComparingTo("75000.00"));
    }

    @Test
    void pastDayExpiresAndFutureDayUsesLocalTimezone() {
        LocalDate past = DATE.minusDays(1);
        schedule(past, false, slot(1, "23:30", false));
        when(source.occupancy(1, past)).thenReturn(new Occupancy(1L, past, List.of()));
        assertThat(service.find(1, past).getFirst().status()).isEqualTo(Status.EXPIRED);
        LocalDate future = DATE.plusDays(1);
        schedule(future, false, slot(1, "00:00", false));
        when(source.occupancy(1, future)).thenReturn(new Occupancy(1L, future, List.of()));
        assertThat(service.find(1, future).getFirst().status()).isEqualTo(Status.AVAILABLE);
    }

    @Test
    void supportsAll48SlotsIncludingMidnightAndCourtClosure() {
        Slot[] slots = new Slot[48];
        for (int index = 0; index < 48; index++) {
            slots[index] = slot(index + 1, LocalTime.MIDNIGHT.plusMinutes(index * 30).toString(), false);
        }
        schedule(DATE, true, slots);
        var result = service.find(1, DATE);
        assertThat(result).hasSize(48);
        assertThat(result.getLast().endTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(result.getLast().status()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void noTemplatesReturnsEmptyButMissingCourtIs404() {
        schedule(DATE, false);
        assertThat(service.find(1, DATE)).isEmpty();
        verifyNoInteractions(holds);
        when(source.schedule(1, DATE)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.find(1, DATE)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void rejectsMalformedSchedulesRatherThanReportingAvailability() {
        var valid = slot(1, "11:00", false);
        var badSlots = List.of(
                List.of(valid, valid),
                List.of(valid, slot(2, "11:15", false)),
                List.of(new Slot(1L, LocalTime.NOON, LocalTime.NOON.plusHours(1), BigDecimal.ONE, false)),
                List.of(new Slot(1L, LocalTime.NOON, LocalTime.NOON.plusMinutes(30), null, false)),
                List.of(new Slot(1L, LocalTime.NOON, LocalTime.NOON.plusMinutes(30), BigDecimal.ONE.negate(), false)),
                List.of(new Slot(1L, LocalTime.NOON, LocalTime.NOON.plusMinutes(30), BigDecimal.ONE, null)));
        for (var slots : badSlots) {
            when(source.schedule(1, DATE)).thenReturn(new Schedule(1L, DATE, false, slots));
            assert503(() -> service.find(1, DATE));
        }
        when(source.schedule(1, DATE)).thenReturn(new Schedule(2L, DATE, false, List.of(valid)));
        assert503(() -> service.find(1, DATE));
        when(source.schedule(1, DATE)).thenReturn(new Schedule(1L, DATE.plusDays(1), false, List.of(valid)));
        assert503(() -> service.find(1, DATE));
    }

    @Test
    void rejectsUnknownBookingsAndMissingOccupancy() {
        schedule(DATE, false, slot(1, "11:00", false));
        when(source.occupancy(1, DATE)).thenReturn(new Occupancy(1L, DATE, List.of(99L)));
        assert503(() -> service.find(1, DATE));
        when(source.occupancy(1, DATE)).thenReturn(null);
        assert503(() -> service.find(1, DATE));
    }

    @Test
    void refreshesHoldOnEveryRequestWithoutCaching() {
        schedule(DATE, false, slot(1, "11:00", false));
        when(holds.heldSlots(anyLong(), any(), anyList())).thenReturn(Set.of(1L), Set.of());
        assertThat(service.find(1, DATE).getFirst().status()).isEqualTo(Status.HOLD);
        assertThat(service.find(1, DATE).getFirst().status()).isEqualTo(Status.AVAILABLE);
    }

    @Test
    void rejectsInvalidInputBeforeCallingUpstream() {
        assertThatThrownBy(() -> service.find(0, DATE)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(source, holds);
    }

    private void assert503(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
}
