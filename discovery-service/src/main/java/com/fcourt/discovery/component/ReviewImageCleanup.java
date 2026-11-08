package com.fcourt.discovery.component;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes review photos that were uploaded but never attached to a review. Only objects under the neutral prefix
 * {@code reviews/} that are older than {@code min-age-hours} and whose key appears in no review are removed.
 * Idempotent (deleting a missing object succeeds) and safe to run on several instances at once.
 * Dry run is the default ({@code dry-run=true}): it only logs what would go; deleting needs an explicit
 * {@code dry-run=false}. Works with any {@link ImageStorage} that supports listing and deleting.
 */
@Component
public class ReviewImageCleanup {
    private static final Logger log = LoggerFactory.getLogger(ReviewImageCleanup.class);
    static final String PREFIX = "reviews/";
    static final int MAX_OBJECTS = 2000;

    public record Result(int scanned, int referenced, int deleted, int wouldDelete, int failed) { }

    private final ImageStorage storage;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration minAge;
    private final boolean enabled, dryRun;

    public ReviewImageCleanup(ImageStorage storage, JdbcTemplate jdbc, Clock clock,
            @Value("${discovery.reviews.images.cleanup.min-age-hours:24}") long minAgeHours,
            @Value("${discovery.reviews.images.cleanup.enabled:true}") boolean enabled,
            @Value("${discovery.reviews.images.cleanup.dry-run:true}") boolean dryRun) {
        this.storage = storage; this.jdbc = jdbc; this.clock = clock;
        this.minAge = Duration.ofHours(Math.max(1, minAgeHours)); this.enabled = enabled; this.dryRun = dryRun;
    }

    @Scheduled(cron = "${discovery.reviews.images.cleanup.cron:0 30 3 * * *}")
    void scheduled() {
        if (!enabled || !storage.supportsCleanup()) return;
        try {
            log.info("Review photo cleanup finished: {}", run(dryRun));
        } catch (RuntimeException error) {
            log.warn("Review photo cleanup aborted: {}", error.getClass().getSimpleName());
        }
    }

    public Result run(boolean dry) {
        var candidates = storage.listByPrefix(PREFIX, clock.instant().minus(minAge), MAX_OBJECTS);
        int scanned = 0, referenced = 0, deleted = 0, would = 0, failed = 0;
        for (var object : candidates) {
            scanned++;
            if (!object.key().startsWith(PREFIX)) continue; // defence in depth: never touch other key spaces
            if (isReferenced(object.key())) { referenced++; continue; }
            if (dry) { would++; log.info("Review photo cleanup (dry run) would delete {}", object.key()); continue; }
            try {
                storage.delete(object.key());
                deleted++;
            } catch (RuntimeException error) {
                failed++; // retried by the next run
            }
        }
        return new Result(scanned, referenced, deleted, would, failed);
    }

    /** Literal substring match (strpos, no LIKE wildcards) of the key in any review's stored image URLs. */
    private boolean isReferenced(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reviews WHERE strpos(image_urls::text, ?) > 0)", Boolean.class, key));
    }
}
