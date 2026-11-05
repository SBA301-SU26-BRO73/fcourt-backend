package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.integration.PlatformStatsClient;
import com.fcourt.reporting.integration.PlatformStatsClient.AccountStats;
import com.fcourt.reporting.integration.PlatformStatsClient.CustomerCounts;
import com.fcourt.reporting.integration.PlatformStatsClient.MonthAmount;
import com.fcourt.reporting.integration.PlatformStatsClient.MonthCount;
import com.fcourt.reporting.integration.PlatformStatsClient.OwnerCounts;
import com.fcourt.reporting.integration.PlatformStatsClient.OwnerName;
import com.fcourt.reporting.integration.PlatformStatsClient.SaasRevenue;
import com.fcourt.reporting.persistence.ReportQueryStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SuperAdminOverviewServiceTest {
    static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    FacilityClient facility;
    PlatformStatsClient platform;
    ReportQueryStore store;
    SuperAdminOverviewService service;

    @BeforeEach
    void setUp() {
        facility = mock(FacilityClient.class);
        platform = mock(PlatformStatsClient.class);
        store = mock(ReportQueryStore.class);
        service = new SuperAdminOverviewService(facility, platform, store, Clock.fixed(NOW, ZoneOffset.UTC),
                "Asia/Ho_Chi_Minh");
    }

    static OwnedBranch branch(long id, long owner, String status, int courts) {
        return new OwnedBranch(id, owner, status, LocalTime.of(6, 0), LocalTime.of(22, 0), courts);
    }

    @Test
    void combinesFourSourcesAndKeepsGmvSeparateFromSaasRevenue() {
        when(facility.allBranches("tok")).thenReturn(List.of(branch(1, 10, "ACTIVE", 3), branch(2, 10, "ACTIVE", 2),
                branch(3, 11, "INACTIVE", 1), branch(4, 12, "ACTIVE", 4)));
        when(platform.accounts(eq("tok"), eq(LocalDate.of(2026, 9, 1)), eq("Asia/Ho_Chi_Minh"))).thenReturn(
                new AccountStats(new OwnerCounts(3, 2, 1, 0, 6), new CustomerCounts(40, 35),
                        List.of(new MonthCount("2026-10", 9), new MonthCount("2026-11", 4))));
        when(platform.saasRevenue(eq("tok"), eq(LocalDate.of(2026, 9, 1)), eq("Asia/Ho_Chi_Minh"))).thenReturn(
                new SaasRevenue(new BigDecimal("900000"), 3, List.of(new MonthAmount("2026-10", new BigDecimal("600000")))));
        when(store.totalRevenue()).thenReturn(new BigDecimal("5000000"));
        var gmv = new LinkedHashMap<String, BigDecimal>();
        gmv.put("2026-09", new BigDecimal("1000000"));
        gmv.put("2026-11", new BigDecimal("2500000"));
        when(store.revenueByMonth(any())).thenReturn(gmv);
        when(store.revenueByBranch()).thenReturn(Map.of(1L, new BigDecimal("1000000"), 2L, new BigDecimal("500000"),
                3L, new BigDecimal("1200000"), 4L, new BigDecimal("1200000"), 99L, new BigDecimal("77")));
        when(platform.ownerNames(eq("tok"), any())).thenReturn(List.of(new OwnerName(10, "Chủ A", "a@x.vn"),
                new OwnerName(11, " ", "b@x.vn")));

        var overview = service.overview("tok", 3);

        assertThat(overview.owners().active()).isEqualTo(3);
        assertThat(overview.owners().pending()).isEqualTo(2);
        assertThat(overview.branches().total()).isEqualTo(4);
        assertThat(overview.branches().active()).isEqualTo(3);
        assertThat(overview.courts()).isEqualTo(10);
        assertThat(overview.customers().total()).isEqualTo(40);
        assertThat(overview.gmv()).isEqualByComparingTo("5000000");
        assertThat(overview.saasRevenue()).isEqualByComparingTo("900000");
        assertThat(overview.monthly()).extracting(p -> p.month()).containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(overview.monthly().get(0).newAccounts()).isZero();
        assertThat(overview.monthly().get(0).gmv()).isEqualByComparingTo("1000000");
        assertThat(overview.monthly().get(1).saasRevenue()).isEqualByComparingTo("600000");
        assertThat(overview.monthly().get(1).gmv()).isZero();
        assertThat(overview.monthly().get(2).newAccounts()).isEqualTo(4);
        // Owners aggregate their branches (10: 1.5M), ties break by id, unknown branch 99 is not attributed.
        assertThat(overview.topOwners()).extracting(o -> o.ownerId()).containsExactly(10L, 11L, 12L);
        assertThat(overview.topOwners().get(0).revenue()).isEqualByComparingTo("1500000");
        assertThat(overview.topOwners().get(0).branches()).isEqualTo(2);
        assertThat(overview.topOwners().get(0).ownerName()).isEqualTo("Chủ A");
        assertThat(overview.topOwners().get(1).ownerName()).isEqualTo("b@x.vn");
        assertThat(overview.topOwners().get(2).ownerName()).isEqualTo("Chủ sân #12");
        assertThat(overview.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void topOwnersAreCappedAtFive() {
        var branches = new java.util.ArrayList<OwnedBranch>();
        var revenue = new java.util.HashMap<Long, BigDecimal>();
        for (long i = 1; i <= 7; i++) {
            branches.add(branch(i, i, "ACTIVE", 1));
            revenue.put(i, BigDecimal.valueOf(i * 100));
        }
        when(facility.allBranches("tok")).thenReturn(branches);
        when(platform.accounts(any(), any(), any())).thenReturn(
                new AccountStats(new OwnerCounts(0, 0, 0, 0, 0), new CustomerCounts(0, 0), List.of()));
        when(platform.saasRevenue(any(), any(), any())).thenReturn(new SaasRevenue(BigDecimal.ZERO, 0, List.of()));
        when(store.totalRevenue()).thenReturn(BigDecimal.ZERO);
        when(store.revenueByMonth(any())).thenReturn(Map.of());
        when(store.revenueByBranch()).thenReturn(revenue);
        when(platform.ownerNames(any(), any())).thenReturn(List.of());

        assertThat(service.overview("tok", 1).topOwners()).extracting(o -> o.ownerId())
                .containsExactly(7L, 6L, 5L, 4L, 3L);
    }

    @Test
    void monthsOutOfRangeAreBadRequests() {
        for (int months : new int[] {0, -1, 25}) {
            assertThatThrownBy(() -> service.overview("tok", months)).isInstanceOfSatisfying(
                    ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(facility, platform, store);
    }

    @Test
    void upstreamFailuresPropagateInsteadOfShowingZeros() {
        when(facility.allBranches("tok")).thenReturn(List.of());
        when(platform.accounts(any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.overview("tok", 3)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
