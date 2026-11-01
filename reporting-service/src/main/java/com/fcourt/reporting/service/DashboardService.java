package com.fcourt.reporting.service;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReadModelStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owner dashboard (SCRUM-12). Branch scope comes from facility-service for the caller's token;
 * every figure is then read from the local read model, filtered by exactly those branch ids.
 */
@Service
public class DashboardService {
    static final int SLOT_MINUTES = 30;

    public record DashboardStats(Long branchId, BigDecimal totalRevenue, BigDecimal todayRevenue, long totalBookings,
                                 Map<String, Long> bookingStatusCounts, int activeCourts, int totalCourts,
                                 double occupancyRate, Instant generatedAt) { }

    private final FacilityClient facility;
    private final ReadModelStore store;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardService(FacilityClient facility, ReadModelStore store, Clock clock,
                            @Value("${reporting.time-zone}") String zone) {
        this.facility = facility;
        this.store = store;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public DashboardStats stats(long adminId, String bearer, Long branchId) {
        List<OwnedBranch> branches = branchId == null ? facility.ownedBranches(adminId, bearer)
                : List.of(facility.ownedBranch(branchId, adminId, bearer));
        List<Long> ids = branches.stream().map(OwnedBranch::id).toList();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);

        var totals = store.totals(ids);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("CONFIRMED", "PENDING", "CANCELLED")) {
            counts.put(status, totals.counts().getOrDefault(status, 0L));
        }
        long totalBookings = counts.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal todayRevenue = store.revenueBetween(ids, today.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant());

        Map<Long, Integer> active = store.activeCourts(ids);
        Map<Long, Long> booked = store.bookedSlots(ids, today);
        int activeCourts = 0;
        int totalCourts = 0;
        long capacity = 0;
        long bookedSlots = 0;
        for (OwnedBranch branch : branches) {
            int branchActive = active.getOrDefault(branch.id(), 0);
            activeCourts += branchActive;
            totalCourts += branch.courtCount();
            long minutesOpen = Math.max(0, Duration.between(branch.openTime(), branch.closeTime()).toMinutes());
            capacity += branchActive * (minutesOpen / SLOT_MINUTES);
            bookedSlots += booked.getOrDefault(branch.id(), 0L);
        }
        double occupancy = capacity == 0 ? 0.0 : Math.min(1.0, BigDecimal.valueOf(bookedSlots)
                .divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP).doubleValue());

        return new DashboardStats(branchId, totals.revenue(), todayRevenue, totalBookings, counts, activeCourts,
                totalCourts, occupancy, now);
    }
}
