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
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BranchSearchService {
    private final BranchSearchRepository repository;
    private final Cache<SearchKey, BranchSearchResponse> cache;
    private final AtomicLong revision = new AtomicLong();

    public BranchSearchService(BranchSearchRepository repository,
                               @Value("${discovery.search.cache-ttl-seconds:5}") long ttlSeconds) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("cache TTL must be positive");
        this.repository = repository;
        this.cache = Caffeine.newBuilder().maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).build();
    }

    public BranchSearchResponse search(BranchSearchRequest request) {
        var key = new SearchKey(revision.get(), request);
        return cache.get(key, ignored -> repository.search(request));
    }

    @TransactionalEventListener
    public void projectionChanged(BranchProjectionChanged event) {
        // A load racing a commit can populate only its old revision, never the new one.
        revision.incrementAndGet();
        // Old revision entries expire naturally within the bounded cache.
    }

    private record SearchKey(long revision, BranchSearchRequest request) {}
}
