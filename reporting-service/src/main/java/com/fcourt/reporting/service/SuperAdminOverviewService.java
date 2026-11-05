package com.fcourt.reporting.service;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.integration.PlatformStatsClient;
import com.fcourt.reporting.integration.PlatformStatsClient.AccountStats;
import com.fcourt.reporting.integration.PlatformStatsClient.MonthAmount;
import com.fcourt.reporting.integration.PlatformStatsClient.MonthCount;
import com.fcourt.reporting.integration.PlatformStatsClient.OwnerName;
import com.fcourt.reporting.integration.PlatformStatsClient.SaasRevenue;
import com.fcourt.reporting.persistence.ReportQueryStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * SUPER_ADMIN platform overview (SCRUM-78). Two money figures that never overlap:
 * {@code gmv} = recognized booking revenue (read model, all branches); {@code saasRevenue} = PAID plan purchases
 * by owners (subscription-service). Counts of accounts come from auth-service, branches/courts from facility-service.
 */
@Service
public class SuperAdminOverviewService {
    public static final int DEFAULT_MONTHS = 12;
    public static final int MAX_MONTHS = 24;
    static final int TOP_OWNERS = 5;

    public record Owners(long active, long pending, long rejected, long total) { }

    public record Branches(long total, long active) { }

    public record Customers(long total, long active) { }

    public record MonthlyPoint(String month, long newAccounts, BigDecimal gmv, BigDecimal saasRevenue) { }

    public record TopOwner(long ownerId, String ownerName, BigDecimal revenue, int branches) { }

    public record Overview(Owners owners, Branches branches, long courts, Customers customers, BigDecimal gmv,
                           BigDecimal saasRevenue, List<MonthlyPoint> monthly, List<TopOwner> topOwners,
                           Instant generatedAt) { }

    private final FacilityClient facility;
    private final PlatformStatsClient platform;
    private final ReportQueryStore store;
    private final Clock clock;
    private final ZoneId zone;

    public SuperAdminOverviewService(FacilityClient facility, PlatformStatsClient platform, ReportQueryStore store,
                                     Clock clock, @Value("${reporting.time-zone}") String zone) {
        this.facility = facility;
        this.platform = platform;
        this.store = store;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public Overview overview(String bearer, int months) {
        if (months < 1 || months > MAX_MONTHS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "months must be between 1 and " + MAX_MONTHS);
        }
        Instant now = clock.instant();
        YearMonth current = YearMonth.from(now.atZone(zone));
        List<YearMonth> window = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) window.add(current.minusMonths(i));
        LocalDate from = window.get(0).atDay(1);

        List<OwnedBranch> branches = facility.allBranches(bearer);
        AccountStats accounts = platform.accounts(bearer, from, zone.getId());
        SaasRevenue saas = platform.saasRevenue(bearer, from, zone.getId());

        Map<String, BigDecimal> gmvByMonth = store.revenueByMonth(from);
        Map<String, Long> accountsByMonth = accounts.newAccountsByMonth().stream()
                .collect(Collectors.toMap(MonthCount::month, MonthCount::count, Long::sum));
        Map<String, BigDecimal> saasByMonth = saas.byMonth().stream()
                .collect(Collectors.toMap(MonthAmount::month, MonthAmount::amount, BigDecimal::add));
        List<MonthlyPoint> monthly = window.stream().map(month -> {
            String key = month.toString();
            return new MonthlyPoint(key, accountsByMonth.getOrDefault(key, 0L),
                    gmvByMonth.getOrDefault(key, BigDecimal.ZERO), saasByMonth.getOrDefault(key, BigDecimal.ZERO));
        }).toList();

        long activeBranches = branches.stream().filter(branch -> "ACTIVE".equals(branch.status())).count();
        long courts = branches.stream().mapToLong(OwnedBranch::courtCount).sum();
        var owners = accounts.owners();
        return new Overview(new Owners(owners.active(), owners.pending(), owners.rejected(), owners.total()),
                new Branches(branches.size(), activeBranches),
                courts, new Customers(accounts.customers().total(), accounts.customers().active()),
                store.totalRevenue(), saas.total(), monthly, topOwners(bearer, branches), now);
    }

    private List<TopOwner> topOwners(String bearer, List<OwnedBranch> branches) {
        Map<Long, BigDecimal> byBranch = store.revenueByBranch();
        Map<Long, BigDecimal> revenue = new HashMap<>();
        Map<Long, Integer> branchCount = new HashMap<>();
        for (OwnedBranch branch : branches) {
            BigDecimal value = byBranch.get(branch.id());
            if (value == null) continue;
            revenue.merge(branch.adminId(), value, BigDecimal::add);
            branchCount.merge(branch.adminId(), 1, Integer::sum);
        }
        List<Map.Entry<Long, BigDecimal>> top = revenue.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_OWNERS).toList();
        Map<Long, String> names = platform.ownerNames(bearer, top.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(OwnerName::id, owner -> displayName(owner), (a, b) -> a));
        return top.stream().map(entry -> new TopOwner(entry.getKey(),
                names.getOrDefault(entry.getKey(), "Chủ sân #" + entry.getKey()), entry.getValue(),
                branchCount.get(entry.getKey()))).toList();
    }

    private static String displayName(OwnerName owner) {
        return owner.fullName() == null || owner.fullName().isBlank() ? owner.email() : owner.fullName();
    }
}
