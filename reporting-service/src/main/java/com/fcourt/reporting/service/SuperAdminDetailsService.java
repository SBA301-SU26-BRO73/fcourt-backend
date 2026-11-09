package com.fcourt.reporting.service;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Detail lists behind the SUPER_ADMIN overview cards (active branches, courts, subscriptions, revenue per plan).
 * Nothing is stored in reporting: branches and courts come from facility-service, subscriptions and plan revenue
 * from subscription-service, owner names from auth-service; the caller's SUPER_ADMIN token is forwarded to each.
 */
@Service
public class SuperAdminDetailsService {
    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";

    public record BranchRow(long id, String name, long ownerId, String ownerName, String ownerEmail, String address,
                            String ward, String district, String city, String phone, String status, int courtCount) { }

    public record CourtRow(long id, String name, long branchId, String branchName, Long courtTypeId,
                           String courtTypeName, String status) { }

    public record SubscriptionItem(long id, long ownerId, String ownerName, String ownerEmail, long planId,
                                   String planName, int maxCourts, int maxBranches, LocalDate startDate,
                                   LocalDate endDate, String status, boolean starter, BigDecimal price) { }

    /** {@code sold} and {@code revenue} count PAID checkouts; {@code active}/{@code expired} count subscription rows. */
    public record PlanSales(long planId, String planName, long sold, long active, long expired, BigDecimal revenue) { }

    public record Details(List<BranchRow> activeBranches, List<CourtRow> courts, List<SubscriptionItem> subscriptions,
                          List<PlanSales> planSales, long activeSubscriptionCount, long expiredSubscriptionCount,
                          Instant generatedAt) { }

    private final FacilityClient facility;
    private final PlatformStatsClient platform;
    private final Clock clock;
    private final ZoneId zone;

    public SuperAdminDetailsService(FacilityClient facility, PlatformStatsClient platform, Clock clock,
                                    @Value("${reporting.time-zone}") String zone) {
        this.facility = facility;
        this.platform = platform;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public Details details(String bearer) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        List<BranchDetail> branches = facility.branchDetails(bearer);
        SubscriptionOverview subscriptions = platform.subscriptions(bearer);
        Map<Long, String> courtTypes = platform.courtTypeNames();

        Set<Long> ownerIds = new LinkedHashSet<>();
        branches.forEach(branch -> ownerIds.add(branch.adminId()));
        subscriptions.subscriptions().forEach(row -> ownerIds.add(row.adminId()));
        Map<Long, OwnerName> owners = platform.ownerNames(bearer, List.copyOf(ownerIds)).stream()
                .collect(Collectors.toMap(OwnerName::id, owner -> owner, (a, b) -> a));

        List<BranchRow> activeBranches = branches.stream().filter(branch -> ACTIVE.equals(branch.status()))
                .map(branch -> branchRow(branch, owners.get(branch.adminId()))).toList();

        List<CourtRow> courts = new ArrayList<>();
        for (BranchDetail branch : branches) {
            if (branch.courtCount() == null || branch.courtCount() == 0) continue;
            for (CourtDetail court : facility.courtDetails(branch.id(), bearer)) {
                courts.add(new CourtRow(court.id(), court.name(), branch.id(), branch.name(), court.courtTypeId(),
                        court.courtTypeId() == null ? null : courtTypes.get(court.courtTypeId()), court.status()));
            }
        }

        List<SubscriptionItem> items = subscriptions.subscriptions().stream()
                .map(row -> subscriptionItem(row, owners.get(row.adminId()), today)).toList();
        long active = items.stream().filter(item -> ACTIVE.equals(item.status())).count();
        return new Details(activeBranches, courts, items, planSales(subscriptions.plans(), items), active,
                items.size() - active, now);
    }

    private static BranchRow branchRow(BranchDetail branch, OwnerName owner) {
        return new BranchRow(branch.id(), branch.name(), branch.adminId(), displayName(owner, branch.adminId()),
                owner == null ? null : owner.email(), branch.address(), branch.ward(), branch.district(),
                branch.city(), branch.phone(), branch.status(), branch.courtCount() == null ? 0 : branch.courtCount());
    }

    private static SubscriptionItem subscriptionItem(SubscriptionRow row, OwnerName owner, LocalDate today) {
        // endDate is inclusive: a subscription ending today is still ACTIVE.
        String status = row.endDate().isBefore(today) ? EXPIRED : ACTIVE;
        return new SubscriptionItem(row.id(), row.adminId(), displayName(owner, row.adminId()),
                owner == null ? null : owner.email(), row.planId(), row.planName(), row.maxCourts(), row.maxBranches(),
                row.startDate(), row.endDate(), status, row.starter(), row.price());
    }

    private static List<PlanSales> planSales(List<PlanRevenue> plans, List<SubscriptionItem> items) {
        Map<Long, long[]> counts = new HashMap<>();
        for (SubscriptionItem item : items) {
            counts.computeIfAbsent(item.planId(), id -> new long[2])[ACTIVE.equals(item.status()) ? 0 : 1]++;
        }
        return plans.stream().map(plan -> {
            long[] count = counts.getOrDefault(plan.planId(), new long[2]);
            return new PlanSales(plan.planId(), plan.planName(), plan.paidCount(), count[0], count[1],
                    plan.revenue() == null ? BigDecimal.ZERO : plan.revenue());
        }).toList();
    }

    private static String displayName(OwnerName owner, long ownerId) {
        if (owner == null) return "Chủ sân #" + ownerId;
        return owner.fullName() == null || owner.fullName().isBlank() ? owner.email() : owner.fullName();
    }
}
