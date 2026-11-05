package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.CourtInfo;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReadModelStore;
import com.fcourt.reporting.persistence.ReadModelStore.CourtAggregate;
import com.fcourt.reporting.persistence.ReadModelStore.CourtFilter;
import com.fcourt.reporting.persistence.ReadModelStore.DayAggregate;
import com.fcourt.reporting.persistence.ReadModelStore.HourAggregate;
import com.fcourt.reporting.service.AnalyticsService.Granularity;
import com.fcourt.reporting.service.AnalyticsService.Period;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsServiceTest {
    static final long OWNER = 7;
    // 2026-11-02T18:00Z is already 2026-11-03 01:00 in Asia/Ho_Chi_Minh.
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"), ZoneOffset.UTC);

    final FacilityClient facility = mock(FacilityClient.class);
    final ReadModelStore store = mock(ReadModelStore.class);
    final AnalyticsService service = new AnalyticsService(facility, store, CLOCK, "Asia/Ho_Chi_Minh");

    /** 06:00-22:00 = 32 half-hour slots per court per day. */
    static OwnedBranch branch(long id) {
        return new OwnedBranch(id, OWNER, "ACTIVE", LocalTime.of(6, 0), LocalTime.of(22, 0), 2);
    }

    static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    static ResponseStatusException status(Throwable error) {
        return (ResponseStatusException) error;
    }

    @Test
    void revenueChartZeroFillsDaysAndComputesOccupancyOverTheSameRange() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(store.activeCourts(List.of(1L))).thenReturn(Map.of(1L, 2));
        when(store.dailyTotals(List.of(1L), d(11, 1), d(11, 3), CourtFilter.NONE)).thenReturn(List.of(
                new DayAggregate(d(11, 1), new BigDecimal("300000"), 2, 4),
                new DayAggregate(d(11, 3), new BigDecimal("100000"), 1, 12)));

        var chart = service.revenueChart(OWNER, "t", null, null, d(11, 1), d(11, 3), Granularity.DAY);

        assertThat(chart.points()).hasSize(3);
        assertThat(chart.points().get(1).revenue()).isEqualByComparingTo("0");
        assertThat(chart.points().get(1).occupancyRate()).isZero();
        // 2 active courts * 32 slots = 64 slots per day.
        assertThat(chart.points().get(0).capacitySlots()).isEqualTo(64);
        assertThat(chart.points().get(0).occupancyRate()).isEqualTo(0.0625);
        assertThat(chart.points().get(2).occupancyRate()).isEqualTo(0.1875);
        assertThat(chart.totalRevenue()).isEqualByComparingTo("400000");
        assertThat(chart.totalBookings()).isEqualTo(3);
        assertThat(chart.capacitySlots()).isEqualTo(192);
        assertThat(chart.occupancyRate()).isEqualTo(0.0833);
        assertThat(chart.occupancyUnit()).isEqualTo("RATIO");
    }

    @Test
    void revenueChartDefaultsToLastThirtyDaysEndingTodayInVietnamTime() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(store.activeCourts(any())).thenReturn(Map.of(1L, 1));
        when(store.dailyTotals(any(), any(), any(), any())).thenReturn(List.of());

        var chart = service.revenueChart(OWNER, "t", null, null, null, null, null);

        assertThat(chart.to()).isEqualTo(d(11, 3));
        assertThat(chart.from()).isEqualTo(d(10, 5));
        assertThat(chart.granularity()).isEqualTo(Granularity.DAY);
        assertThat(chart.points()).hasSize(30);
    }

    @Test
    void revenueChartWithoutDataOrCapacityDividesByZeroSafely() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of());
        when(store.activeCourts(List.of())).thenReturn(Map.of());
        when(store.dailyTotals(any(), any(), any(), any())).thenReturn(List.of());

        var chart = service.revenueChart(OWNER, "t", null, null, d(11, 1), d(11, 7), Granularity.WEEK);

        assertThat(chart.totalRevenue()).isEqualByComparingTo("0");
        assertThat(chart.occupancyRate()).isZero();
        assertThat(chart.points()).allSatisfy(point -> assertThat(point.occupancyRate()).isZero());
    }

    @Test
    void revenueChartCapsOccupancyAtOne() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(store.activeCourts(any())).thenReturn(Map.of(1L, 1));
        when(store.dailyTotals(any(), any(), any(), any()))
                .thenReturn(List.of(new DayAggregate(d(11, 1), BigDecimal.TEN, 1, 500)));

        assertThat(service.revenueChart(OWNER, "t", null, null, d(11, 1), d(11, 1), Granularity.DAY)
                .occupancyRate()).isEqualTo(1.0);
    }

    @Test
    void revenueChartGroupsByWeekAndMonthClippedToTheRange() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(store.activeCourts(any())).thenReturn(Map.of(1L, 1));
        when(store.dailyTotals(any(), any(), any(), any())).thenReturn(List.of(
                new DayAggregate(d(10, 31), new BigDecimal("10"), 1, 1),
                new DayAggregate(d(11, 1), new BigDecimal("20"), 1, 1),
                new DayAggregate(d(11, 2), new BigDecimal("40"), 1, 1)));

        // 2026-10-31 is a Saturday: weeks are [Sat 10-31..Sun 11-01] and [Mon 11-02..Tue 11-03].
        var weeks = service.revenueChart(OWNER, "t", null, null, d(10, 31), d(11, 3), Granularity.WEEK);
        assertThat(weeks.points()).hasSize(2);
        assertThat(weeks.points().get(0).periodStart()).isEqualTo(d(10, 31));
        assertThat(weeks.points().get(0).periodEnd()).isEqualTo(d(11, 1));
        assertThat(weeks.points().get(0).revenue()).isEqualByComparingTo("30");
        assertThat(weeks.points().get(1).revenue()).isEqualByComparingTo("40");
        assertThat(weeks.points().get(1).capacitySlots()).isEqualTo(64);

        var months = service.revenueChart(OWNER, "t", null, null, d(10, 31), d(11, 3), Granularity.MONTH);
        assertThat(months.points()).hasSize(2);
        assertThat(months.points().get(0).revenue()).isEqualByComparingTo("10");
        assertThat(months.points().get(1).periodEnd()).isEqualTo(d(11, 3));
        assertThat(months.points().get(1).revenue()).isEqualByComparingTo("60");
    }

    @Test
    void revenueChartForOneCourtFiltersByCourtAndUsesSingleCourtCapacity() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1), branch(2)));
        when(facility.courts(1L, "t")).thenReturn(List.of(new CourtInfo(10L, 1L, "Court A", "ACTIVE")));
        when(facility.courts(2L, "t")).thenReturn(List.of(new CourtInfo(20L, 2L, " Court B ", "ACTIVE")));
        var filter = new CourtFilter(20L, "court b");
        when(store.dailyTotals(List.of(2L), d(11, 1), d(11, 1), filter))
                .thenReturn(List.of(new DayAggregate(d(11, 1), new BigDecimal("50000"), 1, 8)));

        var chart = service.revenueChart(OWNER, "t", null, 20L, d(11, 1), d(11, 1), Granularity.DAY);

        assertThat(chart.courtId()).isEqualTo(20L);
        assertThat(chart.capacitySlots()).isEqualTo(32);
        assertThat(chart.occupancyRate()).isEqualTo(0.25);
        assertThat(chart.totalRevenue()).isEqualByComparingTo("50000");
    }

    @Test
    void revenueChartRejectsForeignCourtAndInvalidRanges() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(facility.courts(1L, "t")).thenReturn(List.of(new CourtInfo(10L, 1L, "A", "ACTIVE")));

        assertThat(status(catchThrowable(() -> service.revenueChart(OWNER, "t", null, 99L, d(11, 1), d(11, 2), null)))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(catchThrowable(() -> service.revenueChart(OWNER, "t", null, null, d(11, 3), d(11, 2), null)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(catchThrowable(() -> service.revenueChart(OWNER, "t", null, null,
                LocalDate.of(2025, 11, 1), d(11, 2), null))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(service.revenueChart(OWNER, "t", null, null, LocalDate.of(2025, 11, 2), d(11, 2), null)
                .points()).hasSize(366);
    }

    @Test
    void periodRangesCoverMonthQuarterAndYear() {
        assertThat(AnalyticsService.range(Period.MONTH, LocalDate.of(2028, 2, 10)))
                .containsExactly(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
        assertThat(AnalyticsService.range(Period.QUARTER, d(11, 3))).containsExactly(d(10, 1), LocalDate.of(2026, 12, 31));
        assertThat(AnalyticsService.range(Period.QUARTER, d(3, 31))).containsExactly(d(1, 1), d(3, 31));
        assertThat(AnalyticsService.range(Period.YEAR, d(6, 15))).containsExactly(d(1, 1), LocalDate.of(2026, 12, 31));
    }

    @Test
    void topCourtsMergesPaymentNameRowsWithWalkInIdRowsAndRanksByBookingsThenRevenue() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(facility.courts(1L, "t")).thenReturn(List.of(new CourtInfo(10L, 1L, "Court A", "ACTIVE"),
                new CourtInfo(11L, 1L, "Court B", "ACTIVE"), new CourtInfo(12L, 1L, "Court C", "ACTIVE")));
        when(store.courtTotals(List.of(1L), d(11, 1), d(11, 30))).thenReturn(List.of(
                new CourtAggregate(1, null, "court a ", 3, 6, new BigDecimal("300000")),
                new CourtAggregate(1, 10L, null, 3, 2, new BigDecimal("100000")),
                new CourtAggregate(1, null, "Court B", 5, 5, new BigDecimal("500000")),
                new CourtAggregate(1, null, "Court C", 5, 5, new BigDecimal("700000")),
                new CourtAggregate(1, null, "Deleted Court", 1, 1, new BigDecimal("1"))));

        var top = service.topCourts(OWNER, "t", null, Period.MONTH, d(11, 15), 3);

        assertThat(top.from()).isEqualTo(d(11, 1));
        assertThat(top.to()).isEqualTo(d(11, 30));
        assertThat(top.courts()).extracting("courtName").containsExactly("Court A", "Court C", "Court B");
        assertThat(top.courts().get(0).rank()).isEqualTo(1);
        assertThat(top.courts().get(0).bookings()).isEqualTo(6);
        assertThat(top.courts().get(0).bookedSlots()).isEqualTo(8);
        assertThat(top.courts().get(0).revenue()).isEqualByComparingTo("400000");
        assertThat(top.courts().get(0).courtId()).isEqualTo(10L);
        assertThat(top.courts().get(1).revenue()).isEqualByComparingTo("700000");
    }

    @Test
    void topCourtsDefaultsToFiveForTheCurrentMonthAndHandlesNoData() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(facility.courts(eq(1L), any())).thenReturn(List.of());
        when(store.courtTotals(List.of(1L), d(11, 1), d(11, 30))).thenReturn(List.of());

        var top = service.topCourts(OWNER, "t", null, null, null, null);

        assertThat(top.period()).isEqualTo(Period.MONTH);
        assertThat(top.courts()).isEmpty();
        assertThatThrownBy(() -> service.topCourts(OWNER, "t", null, null, null, 0))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.topCourts(OWNER, "t", null, null, null, 11))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void topCourtsKeepsOnlyFiveWhenMoreCourtsHaveBookings() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(facility.courts(eq(1L), any())).thenReturn(List.of());
        var rows = new java.util.ArrayList<CourtAggregate>();
        for (long id = 1; id <= 7; id++) rows.add(new CourtAggregate(1, id, null, id, id, BigDecimal.valueOf(id)));
        when(store.courtTotals(any(), any(), any())).thenReturn(rows);

        var top = service.topCourts(OWNER, "t", null, Period.YEAR, d(11, 3), null);

        assertThat(top.courts()).hasSize(5);
        assertThat(top.courts().get(0).courtId()).isEqualTo(7L);
        assertThat(top.courts().get(0).courtName()).isEqualTo("Court #7");
    }

    @Test
    void peakHoursReturnsAll24HoursAndFlagsHoursNearTheBusiest() {
        when(facility.ownedBranch(1L, OWNER, "t")).thenReturn(branch(1));
        when(store.hourlyTotals(List.of(1L), d(10, 1), LocalDate.of(2026, 12, 31), CourtFilter.NONE)).thenReturn(List.of(
                new HourAggregate(6, 2, 2), new HourAggregate(17, 8, 12), new HourAggregate(18, 10, 15),
                new HourAggregate(19, 9, 9), new HourAggregate(21, 7, 7)));

        var peak = service.peakHours(OWNER, "t", 1L, Period.QUARTER, d(11, 3));

        assertThat(peak.hours()).hasSize(24);
        assertThat(peak.totalSlots()).isEqualTo(45);
        assertThat(peak.hours().stream().filter(AnalyticsService.HourBucket::peak).map(AnalyticsService.HourBucket::hour))
                .containsExactly(17, 18, 19);
        assertThat(peak.hours().get(0).bookings()).isZero();
        assertThat(peak.hours().get(18).slots()).isEqualTo(15);
    }

    @Test
    void peakHoursWithoutBookingsHasNoPeak() {
        when(facility.ownedBranches(OWNER, "t")).thenReturn(List.of(branch(1)));
        when(store.hourlyTotals(any(), any(), any(), any())).thenReturn(List.of());

        var peak = service.peakHours(OWNER, "t", null, null, null);

        assertThat(peak.hours()).hasSize(24).noneMatch(AnalyticsService.HourBucket::peak);
        assertThat(peak.totalSlots()).isZero();
    }

    @Test
    void ratioIsZeroWithoutCapacity() {
        assertThat(AnalyticsService.ratio(5, 0)).isZero();
        assertThat(AnalyticsService.ratio(1, 3)).isEqualTo(0.3333);
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            return error;
        }
        throw new AssertionError("Expected an exception");
    }
}
