package com.fcourt.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.BranchDetail;
import com.fcourt.reporting.integration.FacilityClient.CourtDetail;
import com.fcourt.reporting.integration.PlatformStatsClient;
import com.fcourt.reporting.integration.PlatformStatsClient.OwnerName;
import com.fcourt.reporting.integration.PlatformStatsClient.PlanRevenue;
import com.fcourt.reporting.integration.PlatformStatsClient.SubscriptionOverview;
import com.fcourt.reporting.integration.PlatformStatsClient.SubscriptionRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuperAdminDetailsServiceTest {
    static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    FacilityClient facility;
    PlatformStatsClient platform;
    SuperAdminDetailsService service;

    @BeforeEach
    void setUp() {
        facility = mock(FacilityClient.class);
        platform = mock(PlatformStatsClient.class);
        service = new SuperAdminDetailsService(facility, platform, Clock.fixed(NOW, ZoneOffset.UTC),
                "Asia/Ho_Chi_Minh");
    }

    static BranchDetail branch(long id, long owner, String name, String status, int courts) {
        return new BranchDetail(id, owner, name, "1 Le Loi", "Ben Nghe", "Q1", "HCM", "0900000000", status, courts);
    }

    static SubscriptionRow sub(long id, long owner, long plan, String planName, String end, boolean starter,
                               String price) {
        return new SubscriptionRow(id, owner, plan, planName, 10, 3, LocalDate.of(2026, 1, 1), LocalDate.parse(end),
                starter, price == null ? null : new BigDecimal(price));
    }

    @Test
    void buildsBranchCourtSubscriptionAndPlanLists() {
        when(facility.branchDetails("tok")).thenReturn(List.of(branch(1, 10, "Alpha", "ACTIVE", 2),
                branch(2, 11, "Beta", "INACTIVE", 1), branch(3, 10, "Gamma", "ACTIVE", 0)));
        when(facility.courtDetails(1, "tok")).thenReturn(List.of(new CourtDetail(100L, 1L, "S1", 5L, "ACTIVE"),
                new CourtDetail(101L, 1L, "S2", 6L, "MAINTENANCE")));
        when(facility.courtDetails(2, "tok")).thenReturn(List.of(new CourtDetail(200L, 2L, "B1", 99L, "ACTIVE")));
        when(platform.courtTypeNames()).thenReturn(Map.of(5L, "Cau long", 6L, "Pickleball"));
        when(platform.subscriptions("tok")).thenReturn(new SubscriptionOverview(List.of(
                sub(1, 10, 2, "Pro", "2026-11-05", false, "500000"),
                sub(2, 11, 2, "Pro", "2026-11-04", false, "500000"),
                sub(3, 12, 1, "Free", "2027-01-01", true, null)),
                List.of(new PlanRevenue(1, "Free", 0, BigDecimal.ZERO), new PlanRevenue(2, "Pro", 2,
                        new BigDecimal("1000000")), new PlanRevenue(3, "Max", 0, null))));
        when(platform.ownerNames(any(), any())).thenReturn(List.of(new OwnerName(10, "Chu A", "a@x.vn"),
                new OwnerName(11, " ", "b@x.vn")));

        var details = service.details("tok");

        assertThat(details.activeBranches()).extracting(b -> b.id()).containsExactly(1L, 3L);
        assertThat(details.activeBranches().get(0).ownerName()).isEqualTo("Chu A");
        assertThat(details.activeBranches().get(0).courtCount()).isEqualTo(2);
        verify(facility, never()).courtDetails(3, "tok");
        assertThat(details.courts()).extracting(c -> c.id()).containsExactly(100L, 101L, 200L);
        assertThat(details.courts().get(0).branchName()).isEqualTo("Alpha");
        assertThat(details.courts().get(0).courtTypeName()).isEqualTo("Cau long");
        assertThat(details.courts().get(2).courtTypeName()).isNull();
        // endDate is inclusive, so the one ending today is still ACTIVE (today is 2026-11-05 in Vietnam).
        assertThat(details.subscriptions()).extracting(s -> s.status()).containsExactly("ACTIVE", "EXPIRED", "ACTIVE");
        assertThat(details.subscriptions().get(1).ownerName()).isEqualTo("b@x.vn");
        assertThat(details.subscriptions().get(2).ownerName()).isEqualTo("Chủ sân #12");
        assertThat(details.activeSubscriptionCount()).isEqualTo(2);
        assertThat(details.expiredSubscriptionCount()).isEqualTo(1);
        var pro = details.planSales().get(1);
        assertThat(pro.planName()).isEqualTo("Pro");
        assertThat(pro.sold()).isEqualTo(2);
        assertThat(pro.active()).isEqualTo(1);
        assertThat(pro.expired()).isEqualTo(1);
        assertThat(pro.revenue()).isEqualByComparingTo("1000000");
        assertThat(details.planSales().get(2).revenue()).isZero();
        assertThat(details.generatedAt()).isEqualTo(NOW);
    }
}
