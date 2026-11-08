package com.fcourt.discovery.component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user cap on uploaded review photos: at most {@code max-files} per fixed window. State is in memory, per instance
 * (no Redis round trip on the upload path), so with N instances the effective cap is up to N times higher; that is
 * acceptable for abuse control. Memory is bounded: expired windows are purged once the map passes {@code PURGE_AT}.
 */
@Component
public class ReviewUploadLimiter {
    static final int PURGE_AT = 10_000;
    private record Window(Instant start, int used) { }

    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final int maxFiles;
    private final long windowSeconds;
    private final Clock clock;

    public ReviewUploadLimiter(@Value("${discovery.reviews.images.rate-limit.max-files:20}") int maxFiles,
            @Value("${discovery.reviews.images.rate-limit.window-seconds:600}") long windowSeconds, Clock clock) {
        this.maxFiles = maxFiles; this.windowSeconds = windowSeconds; this.clock = clock;
    }

    /** Reserves {@code files} uploads for the user or throws 429; never partially charges. */
    public void acquire(long userId, int files) {
        Instant now = clock.instant();
        if (windows.size() >= PURGE_AT) windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        boolean[] allowed = {true};
        windows.compute(userId, (id, current) -> {
            Window window = current == null || expired(current, now) ? new Window(now, 0) : current;
            if (window.used() + files > maxFiles) { allowed[0] = false; return window; }
            return new Window(window.start(), window.used() + files);
        });
        if (!allowed[0]) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Bạn tải ảnh quá nhiều, vui lòng thử lại sau ít phút");
    }

    int tracked() { return windows.size(); }

    private boolean expired(Window window, Instant now) { return !now.isBefore(window.start().plusSeconds(windowSeconds)); }
}
