package com.fcourt.reporting.service;

import com.fcourt.reporting.integration.FacilityClient;
import com.fcourt.reporting.integration.FacilityClient.CourtInfo;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import com.fcourt.reporting.persistence.ReadModelStore;
import com.fcourt.reporting.persistence.ReadModelStore.CourtAggregate;
import com.fcourt.reporting.persistence.ReadModelStore.CourtFilter;
import com.fcourt.reporting.persistence.ReadModelStore.DayAggregate;
import com.fcourt.reporting.persistence.ReadModelStore.HourAggregate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner analytics (SCRUM-43, SCRUM-74). Same scoping rule as {@link DashboardService}: facility-service decides
 * which branches/courts the caller owns, every figure then comes from the local read model.
 *
 * <p>All dates are booking dates in the reporting time zone (Asia/Ho_Chi_Minh by default). Occupancy is a ratio
 * in [0, 1]: confirmed booked 30-minute slots / bookable slots (active courts x opening minutes / 30 x days),
 * using the current facility configuration for the whole period.
 */
@Service
public class AnalyticsService {
    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 30;
    static final int MAX_TOP_COURTS = 10;
    /** An hour is "peak" when it reaches this share of the busiest hour. */
    static final double PEAK_SHARE = 0.8;

    public enum Granularity { DAY, WEEK, MONTH }

    public enum Period { MONTH, QUARTER, YEAR }

    public record RevenuePoint(LocalDate periodStart, LocalDate periodEnd, BigDecimal revenue, long bookings,
                               long bookedSlots, long capacitySlots, double occupancyRate) { }

    public record RevenueChart(Long branchId, Long courtId, Granularity granularity, LocalDate from, LocalDate to,
                               BigDecimal totalRevenue, long totalBookings, long bookedSlots, long capacitySlots,
                               double occupancyRate, String occupancyUnit, List<RevenuePoint> points,
                               Instant generatedAt) { }

    public record TopCourt(int rank, Long courtId, String courtName, long branchId, long bookings, long bookedSlots,
                           BigDecimal revenue) { }

    public record TopCourts(Long branchId, Period period, LocalDate from, LocalDate to, List<TopCourt> courts,
                            Instant generatedAt) { }

    public record HourBucket(int hour, long bookings, long slots, boolean peak) { }

    public record PeakHours(Long branchId, Period period, LocalDate from, LocalDate to, long totalSlots,
                            List<HourBucket> hours, Instant generatedAt) { }

    private record CourtKey(long branchId, Long courtId, String name) { }

    private final FacilityClient facility;
    private final ReadModelStore store;
    private final Clock clock;
    private final ZoneId zone;

    public AnalyticsService(FacilityClient facility, ReadModelStore store, Clock clock,
                            @Value("${reporting.time-zone}") String zone) {
        this.facility = facility;
        this.store = store;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public RevenueChart revenueChart(long adminId, String bearer, Long branchId, Long courtId, LocalDate from,
                                     LocalDate to, Granularity granularity) {
        Instant now = clock.instant();
        LocalDate end = to != null ? to : LocalDate.ofInstant(now, zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) throw badRequest("'from' must not be after 'to'");
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw badRequest("Range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        Granularity grain = granularity == null ? Granularity.DAY : granularity;

        List<OwnedBranch> branches = ownedBranches(adminId, bearer, branchId);
        List<Long> ids = branches.stream().map(OwnedBranch::id).toList();
        CourtFilter filter = CourtFilter.NONE;
        long capacityPerDay;
        if (courtId != null) {
            CourtInfo court = findCourt(branches, courtId, bearer);
            OwnedBranch owner = branches.stream().filter(branch -> branch.id().equals(court.branchId())).findFirst()
                    .orElseThrow(AnalyticsService::denied);
            ids = List.of(owner.id());
            filter = new CourtFilter(court.id(), CourtFilter.normalize(court.name()));
            capacityPerDay = "ACTIVE".equals(court.status()) ? slotsPerDay(owner) : 0;
        } else {
            Map<Long, Integer> active = store.activeCourts(ids);
            capacityPerDay = branches.stream()
                    .mapToLong(branch -> active.getOrDefault(branch.id(), 0) * slotsPerDay(branch)).sum();
        }

        Map<LocalDate, DayAggregate> byDay = new HashMap<>();
        store.dailyTotals(ids, start, end, filter).forEach(day -> byDay.put(day.day(), day));

        List<RevenuePoint> points = new ArrayList<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalBookings = 0;
        long totalSlots = 0;
        for (LocalDate[] bucket : buckets(start, end, grain)) {
            BigDecimal revenue = BigDecimal.ZERO;
            long bookings = 0;
            long slots = 0;
            for (LocalDate day = bucket[0]; !day.isAfter(bucket[1]); day = day.plusDays(1)) {
                DayAggregate aggregate = byDay.get(day);
                if (aggregate == null) continue;
                revenue = revenue.add(aggregate.revenue());
                bookings += aggregate.bookings();
                slots += aggregate.bookedSlots();
            }
            long capacity = capacityPerDay * (ChronoUnit.DAYS.between(bucket[0], bucket[1]) + 1);
            points.add(new RevenuePoint(bucket[0], bucket[1], revenue, bookings, slots, capacity,
                    ratio(slots, capacity)));
            totalRevenue = totalRevenue.add(revenue);
            totalBookings += bookings;
            totalSlots += slots;
        }
        long totalCapacity = capacityPerDay * (ChronoUnit.DAYS.between(start, end) + 1);
        return new RevenueChart(branchId, courtId, grain, start, end, totalRevenue, totalBookings, totalSlots,
                totalCapacity, ratio(totalSlots, totalCapacity), "RATIO", points, now);
    }

    public TopCourts topCourts(long adminId, String bearer, Long branchId, Period period, LocalDate date,
                               Integer limit) {
        int max = limit == null ? 5 : limit;
        if (max < 1 || max > MAX_TOP_COURTS) throw badRequest("'limit' must be between 1 and " + MAX_TOP_COURTS);
        Instant now = clock.instant();
        Period kind = period == null ? Period.MONTH : period;
        LocalDate[] range = range(kind, date != null ? date : LocalDate.ofInstant(now, zone));
        List<OwnedBranch> branches = ownedBranches(adminId, bearer, branchId);

        // Payment events know a court by name, walk-ins by id: resolve both to the facility court id.
        Map<String, CourtInfo> courtsByName = new HashMap<>();
        Map<Long, CourtInfo> courtsById = new HashMap<>();
        for (OwnedBranch branch : branches) {
            for (CourtInfo court : facility.courts(branch.id(), bearer)) {
                courtsById.put(court.id(), court);
                courtsByName.put(branch.id() + "/" + CourtFilter.normalize(court.name()), court);
            }
        }
        Map<CourtKey, long[]> counts = new HashMap<>();
        Map<CourtKey, BigDecimal> revenue = new HashMap<>();
        Map<CourtKey, String> labels = new HashMap<>();
        List<Long> ids = branches.stream().map(OwnedBranch::id).toList();
        for (CourtAggregate row : store.courtTotals(ids, range[0], range[1])) {
            CourtInfo court = row.courtId() != null ? courtsById.get(row.courtId())
                    : courtsByName.get(row.branchId() + "/" + CourtFilter.normalize(row.courtName()));
            Long resolved = court != null ? court.id() : row.courtId();
            String normalized = resolved == null ? CourtFilter.normalize(row.courtName()) : null;
            CourtKey key = new CourtKey(row.branchId(), resolved, normalized);
            long[] sum = counts.computeIfAbsent(key, k -> new long[2]);
            sum[0] += row.bookings();
            sum[1] += row.bookedSlots();
            revenue.merge(key, row.revenue(), BigDecimal::add);
            labels.putIfAbsent(key, court != null ? court.name()
                    : row.courtName() != null ? row.courtName() : "Court #" + row.courtId());
        }
        List<CourtKey> ranked = new ArrayList<>(counts.keySet());
        ranked.sort(Comparator.<CourtKey>comparingLong(key -> -counts.get(key)[0])
                .thenComparing(key -> revenue.get(key).negate())
                .thenComparing(labels::get));
        List<TopCourt> top = new ArrayList<>();
        for (CourtKey key : ranked.subList(0, Math.min(max, ranked.size()))) {
            top.add(new TopCourt(top.size() + 1, key.courtId(), labels.get(key), key.branchId(), counts.get(key)[0],
                    counts.get(key)[1], revenue.get(key)));
        }
        return new TopCourts(branchId, kind, range[0], range[1], top, now);
    }

    public PeakHours peakHours(long adminId, String bearer, Long branchId, Period period, LocalDate date) {
        Instant now = clock.instant();
        Period kind = period == null ? Period.MONTH : period;
        LocalDate[] range = range(kind, date != null ? date : LocalDate.ofInstant(now, zone));
        List<Long> ids = ownedBranches(adminId, bearer, branchId).stream().map(OwnedBranch::id).toList();
        Map<Integer, HourAggregate> byHour = new HashMap<>();
        store.hourlyTotals(ids, range[0], range[1], CourtFilter.NONE).forEach(row -> byHour.put(row.hour(), row));
        long busiest = byHour.values().stream().mapToLong(HourAggregate::bookings).max().orElse(0);
        List<HourBucket> hours = new ArrayList<>();
        long totalSlots = 0;
        for (int hour = 0; hour < 24; hour++) {
            HourAggregate row = byHour.get(hour);
            long bookings = row == null ? 0 : row.bookings();
            long slots = row == null ? 0 : row.slots();
            totalSlots += slots;
            hours.add(new HourBucket(hour, bookings, slots, busiest > 0 && bookings >= busiest * PEAK_SHARE));
        }
        return new PeakHours(branchId, kind, range[0], range[1], totalSlots, hours, now);
    }

    /** Calendar month / quarter / year containing {@code date}. */
    static LocalDate[] range(Period period, LocalDate date) {
        return switch (period) {
            case MONTH -> new LocalDate[] {date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth())};
            case QUARTER -> {
                LocalDate first = date.withDayOfMonth(1).withMonth((date.getMonthValue() - 1) / 3 * 3 + 1);
                yield new LocalDate[] {first, first.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth())};
            }
            case YEAR -> new LocalDate[] {date.withDayOfYear(1), date.with(TemporalAdjusters.lastDayOfYear())};
        };
    }

    /** Buckets covering [start, end], clipped to it; weeks run Monday-Sunday, months are calendar months. */
    static List<LocalDate[]> buckets(LocalDate start, LocalDate end, Granularity granularity) {
        List<LocalDate[]> result = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate last = switch (granularity) {
                case DAY -> cursor;
                case WEEK -> cursor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case MONTH -> cursor.with(TemporalAdjusters.lastDayOfMonth());
            };
            if (last.isAfter(end)) last = end;
            result.add(new LocalDate[] {cursor, last});
            cursor = last.plusDays(1);
        }
        return result;
    }

    static long slotsPerDay(OwnedBranch branch) {
        long minutesOpen = Math.max(0, Duration.between(branch.openTime(), branch.closeTime()).toMinutes());
        return minutesOpen / DashboardService.SLOT_MINUTES;
    }

    static double ratio(long booked, long capacity) {
        if (capacity <= 0) return 0.0;
        return Math.min(1.0, BigDecimal.valueOf(booked).divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP)
                .doubleValue());
    }

    private List<OwnedBranch> ownedBranches(long adminId, String bearer, Long branchId) {
        return branchId == null ? facility.ownedBranches(adminId, bearer)
                : List.of(facility.ownedBranch(branchId, adminId, bearer));
    }

    private CourtInfo findCourt(List<OwnedBranch> branches, long courtId, String bearer) {
        for (OwnedBranch branch : branches) {
            for (CourtInfo court : facility.courts(branch.id(), bearer)) {
                if (court.id() == courtId) {
                    return new CourtInfo(court.id(), branch.id(), court.name(), court.status());
                }
            }
        }
        throw denied();
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Court access denied");
    }
}
