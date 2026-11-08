package com.fcourt.discovery.service;

import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
import com.fcourt.discovery.dto.response.BranchSearchResponse.BranchSummary;
import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.dto.response.CourtSlotResponse.Status;
import com.fcourt.discovery.repository.BranchSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchAvailabilitySearchTest {
    private final BranchSearchRepository repository = mock(BranchSearchRepository.class);
    private final CourtSlotService slots = mock(CourtSlotService.class);
    // UTC date differs from Vietnam's date.
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T20:00:00Z"), ZoneOffset.UTC);
    private final BranchSearchService service = new BranchSearchService(repository, slots, clock, 5);
    private final LocalDate today = LocalDate.of(2030, 1, 2);
    private final BranchSearchRequest request = new BranchSearchRequest("court", "city", "district", 1L,
            new BigDecimal("50"), new BigDecimal("100"), null, null, 2, 1, true, "18:00 - 19:00");

    private BranchSummary branch(long id) {
        return new BranchSummary(id, "court", "address", "city", "district", null, null,
                BigDecimal.ONE, BigDecimal.TEN, 1, null);
    }

    private CourtSlotResponse slot(String start, String end, String price, Status status) {
        return new CourtSlotResponse(1, LocalTime.parse(start), LocalTime.parse(end), new BigDecimal(price), status);
    }

    private void candidate() {
        when(repository.search(any())).thenReturn(new BranchSearchResponse(List.of(branch(1)), 0, 100, 1, 1));
        when(repository.courtIds(1, 1L)).thenReturn(List.of(10L));
    }

    @Test
    void checksVietnamTodayAndActualPricesThenDelegatesPaginationWithoutCaching() {
        candidate();
        when(slots.find(10, today)).thenReturn(List.of(
                slot("18:30", "19:00", "100", Status.AVAILABLE),
                slot("18:00", "18:30", "50", Status.AVAILABLE)));
        service.search(request);
        service.search(request);
        verify(slots, times(2)).find(10, today);
        verify(repository, times(2)).search(request, List.of(10L));
        verify(repository, times(2)).search(new BranchSearchRequest("court", "city", "district", 1L,
                null, null, null, null, 0, 100));
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"HOLD", "BOOKED", "OUT_OF_SERVICE", "EXPIRED"})
    void rejectsAnyBlockedSlotInsideTheRange(Status status) {
        candidate();
        when(slots.find(10, today)).thenReturn(List.of(slot("18:00", "18:30", "75", Status.AVAILABLE),
                slot("18:30", "19:00", "75", status)));
        service.search(request);
        verify(repository).search(request, List.of());
    }

    @Test
    void rejectsGapsAndSlotsOutsidePriceRange() {
        candidate();
        when(slots.find(10, today)).thenReturn(List.of(slot("18:00", "18:30", "75", Status.AVAILABLE),
                slot("19:00", "19:30", "75", Status.AVAILABLE)))
                .thenReturn(List.of(slot("18:00", "18:30", "49", Status.AVAILABLE),
                        slot("18:30", "19:00", "101", Status.AVAILABLE)));
        service.search(request);
        service.search(request);
        verify(repository, times(2)).search(request, List.of());
    }

    @Test
    void neverCombinesDifferentCourtsToCoverTheRange() {
        candidate();
        when(repository.courtIds(1, 1L)).thenReturn(List.of(10L, 11L));
        when(slots.find(10, today)).thenReturn(List.of(slot("18:00", "18:30", "75", Status.AVAILABLE)));
        when(slots.find(11, today)).thenReturn(List.of(slot("18:30", "19:00", "75", Status.AVAILABLE)));
        service.search(request);
        verify(repository).search(request, List.of());
    }

    @Test
    void checksAllCandidatePagesBeforeFilteringAndCounting() {
        when(repository.search(any())).thenReturn(new BranchSearchResponse(List.of(branch(1)), 0, 100, 101, 2))
                .thenReturn(new BranchSearchResponse(List.of(branch(2)), 1, 100, 101, 2));
        when(repository.courtIds(1, 1L)).thenReturn(List.of(10L));
        when(repository.courtIds(2, 1L)).thenReturn(List.of(20L));
        when(slots.find(10, today)).thenReturn(List.of());
        when(slots.find(20, today)).thenReturn(List.of(slot("18:00", "18:30", "75", Status.AVAILABLE),
                slot("18:30", "19:00", "75", Status.AVAILABLE)));
        service.search(request);
        verify(repository).search(request, List.of(20L));
    }

    @Test
    void timeSlotAloneEnablesAvailabilityAndTodayAloneNeedsOnlyOneSlot() {
        candidate();
        when(slots.find(10, today)).thenReturn(List.of(slot("18:00", "18:30", "75", Status.AVAILABLE)));
        var rangeOnly = new BranchSearchRequest(null, null, null, 1L, null, null,
                null, null, null, null, false, "18:10-18:20");
        service.search(rangeOnly);
        verify(repository).search(rangeOnly, List.of(10L));
        var todayOnly = new BranchSearchRequest(null, null, null, 1L, null, null,
                null, null, null, null, true, null);
        service.search(todayOnly);
        verify(repository).search(todayOnly, List.of(10L));
    }

    @Test
    void dependencyFailureFailsClosedAndIsNotCached() {
        candidate();
        when(slots.find(10, today)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.search(request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search(request)).isInstanceOf(ResponseStatusException.class);
        verify(slots, times(2)).find(10, today);
        verify(repository, never()).search(eq(request), anyList());
    }
}
