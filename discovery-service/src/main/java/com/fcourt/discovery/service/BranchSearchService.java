package com.fcourt.discovery.service;

import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
import com.fcourt.discovery.model.event.BranchProjectionChanged;
import com.fcourt.discovery.repository.BranchSearchRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import com.fcourt.discovery.dto.response.CourtSlotResponse;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BranchSearchService {
    private final BranchSearchRepository repository;
    private final Cache<SearchKey, BranchSearchResponse> cache;
    private final AtomicLong revision = new AtomicLong();
    private final CourtSlotService slots;
    private final Clock clock;

    public BranchSearchService(BranchSearchRepository repository,
                               CourtSlotService slots, Clock clock,
                               @Value("${discovery.search.cache-ttl-seconds:5}") long ttlSeconds) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("cache TTL must be positive");
        this.repository = repository;
        this.slots = slots;
        this.clock = clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
        this.cache = Caffeine.newBuilder().maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).build();
    }

    public BranchSearchResponse search(BranchSearchRequest request) {
        if (request.requiresAvailability()) return searchAvailable(request);
        var key = new SearchKey(revision.get(), repository.ratingVersion(), request);
        return cache.get(key, ignored -> repository.search(request));
    }

    private BranchSearchResponse searchAvailable(BranchSearchRequest request) {
        LocalDate today = LocalDate.now(clock);
        var eligible = new ArrayList<Long>();
        // Scan candidate pages without template price restrictions, then let SQL
        // rank/count/page only courts proven available by the existing slot API.
        int page = 0;
        BranchSearchResponse candidates;
        do {
            var candidateRequest = new BranchSearchRequest(request.query(), request.city(), request.district(),
                    request.courtTypeId(), null, null, request.latitude(), request.longitude(), page, 100);
            candidates = repository.search(candidateRequest);
            for (var branch : candidates.content()) {
                for (long courtId : repository.courtIds(branch.id(), request.courtTypeId())) {
                    if (matches(slots.find(courtId, today), request)) eligible.add(courtId);
                }
            }
            page++;
        } while (page < candidates.totalPages());
        return repository.search(request, eligible);
    }

    private boolean matches(java.util.List<CourtSlotResponse> courtSlots, BranchSearchRequest request) {
        var available = courtSlots.stream()
                .filter(slot -> slot.status() == CourtSlotResponse.Status.AVAILABLE)
                .filter(slot -> request.minPrice() == null || slot.price().compareTo(request.minPrice()) >= 0)
                .filter(slot -> request.maxPrice() == null || slot.price().compareTo(request.maxPrice()) <= 0)
                .sorted(Comparator.comparing(CourtSlotResponse::startTime)).toList();
        if (request.timeSlot() == null) return !available.isEmpty();
        String[] range = request.timeSlot().split(" *- *");
        LocalTime cursor = LocalTime.parse(range[0]);
        LocalTime end = LocalTime.parse(range[1]);
        for (var slot : available) {
            if (slot.startTime().isAfter(cursor)) return false;
            // Midnight is the end of this day for the existing slot contract.
            if (slot.endTime().equals(LocalTime.MIDNIGHT)) return true;
            if (!slot.endTime().isAfter(cursor)) continue;
            cursor = slot.endTime();
            if (!cursor.isBefore(end)) return true;
        }
        return false;
    }

    @TransactionalEventListener
    public void projectionChanged(BranchProjectionChanged event) {
        // A load racing a commit can populate only its old revision, never the new one.
        revision.incrementAndGet();
        // Old revision entries expire naturally within the bounded cache.
    }

    private record SearchKey(long revision, long ratingRevision, BranchSearchRequest request) {}
}
