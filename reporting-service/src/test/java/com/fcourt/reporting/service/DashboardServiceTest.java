package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReadModelStore;
import com.fcourt.reporting.persistence.ReadModelStore.StatusTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {
    @Test
    void todayRevenueIsRevenueOfBookingsDatedToday() {
        var branch = new OwnedBranch(1L, 7L, "ACTIVE", LocalTime.of(6, 0), LocalTime.of(22, 0), 1);
        var facility = mock(FacilityClient.class);
        when(facility.ownedBranches(7L, "token")).thenReturn(List.of(branch));
        var store = mock(ReadModelStore.class);
        when(store.totals(any())).thenReturn(new StatusTotals(Map.of(), new BigDecimal("500000")));
        when(store.revenueOnBookingDate(List.of(1L), LocalDate.of(2026, 11, 2))).thenReturn(new BigDecimal("120000"));
        var clock = Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC);

        var stats = new DashboardService(facility, store, clock, "Asia/Ho_Chi_Minh").stats(7L, "token", null);

        assertThat(stats.todayRevenue()).isEqualByComparingTo("120000");
        assertThat(stats.totalRevenue()).isEqualByComparingTo("500000");
    }
}
