package com.fcourt.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket trong bộ nhớ, theo từng khóa. Dung lượng = limit, nạp đều limit token mỗi window.
 * Chỉ đúng trong một instance; nhiều instance thì mỗi instance giữ giới hạn riêng (gateway sẽ thay thế).
 */
public class TokenBucketLimiter {
    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final int limit;
    private final double nanosPerToken;
    private final int maxKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int limit, Duration window, int maxKeys, Clock clock) {
        this.limit = limit;
        this.nanosPerToken = (double) window.toNanos() / limit;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    private long now() {
        var instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /** @return 0 nếu được phép (đã trừ 1 token), ngược lại số giây phải chờ. */
    public long tryAcquire(String key) {
        long now = now();
        if (buckets.size() >= maxKeys) evict(now);
        var bucket = buckets.computeIfAbsent(key, k -> {
            var created = new Bucket();
            created.tokens = limit;
            created.lastNanos = now;
            return created;
        });
        synchronized (bucket) {
            double refill = Math.max(0, now - bucket.lastNanos) / nanosPerToken;
            bucket.tokens = Math.min(limit, bucket.tokens + refill);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - bucket.tokens) * nanosPerToken / 1_000_000_000d);
        }
    }

    /** Bỏ các bucket đã đầy lại; nếu vẫn quá nhiều thì xóa hết để tránh phình bộ nhớ. */
    private void evict(long now) {
        buckets.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().tokens + Math.max(0, now - e.getValue().lastNanos) / nanosPerToken >= limit;
            }
        });
        if (buckets.size() >= maxKeys) buckets.clear();
    }

    int tracked() { return buckets.size(); }
}
