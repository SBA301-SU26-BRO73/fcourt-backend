package com.fcourt.discovery.component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes review photos that were uploaded but never attached to a review. Only files under {@code fcourt/reviews/}
 * that are older than {@code min-age-hours} and whose public id appears in no review are removed. Idempotent (deleting a
 * missing file succeeds) and safe to run on several instances at once. {@code dry-run=true} only logs what would go.
 */
@Component
public class ReviewImageCleanup {
    private static final Logger log = LoggerFactory.getLogger(ReviewImageCleanup.class);
    static final String PREFIX = "fcourt/reviews/";
    static final int MAX_PAGES = 20;

    public record Result(int scanned, int tooNew, int referenced, int deleted, int wouldDelete, int failed) { }

    private final ReviewImageStorage storage;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration minAge;
    private final boolean enabled, dryRun;

    public ReviewImageCleanup(ReviewImageStorage storage, JdbcTemplate jdbc, Clock clock,
            @Value("${discovery.reviews.images.cleanup.min-age-hours:24}") long minAgeHours,
            @Value("${discovery.reviews.images.cleanup.enabled:true}") boolean enabled,
            @Value("${discovery.reviews.images.cleanup.dry-run:false}") boolean dryRun) {
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
        Instant cutoff = clock.instant().minus(minAge);
        int scanned = 0, tooNew = 0, referenced = 0, deleted = 0, would = 0, failed = 0;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var listing = storage.list(PREFIX, cursor);
            for (var asset : listing.assets()) {
                scanned++;
                if (!asset.publicId().startsWith(PREFIX)) continue; // defence in depth: never touch other folders
                if (!asset.createdAt().isBefore(cutoff)) { tooNew++; continue; }
                if (isReferenced(asset.publicId())) { referenced++; continue; }
                if (dry) { would++; log.info("Review photo cleanup (dry run) would delete {}", asset.publicId()); continue; }
                try {
                    storage.delete(asset.publicId());
                    deleted++;
                } catch (RuntimeException error) {
                    failed++; // retried by the next run
                }
            }
            cursor = listing.nextCursor();
            if (cursor == null) break;
        }
        return new Result(scanned, tooNew, referenced, deleted, would, failed);
    }

    /** Literal substring match (strpos, no LIKE wildcards) of the public id in any review's stored image URLs. */
    private boolean isReferenced(String publicId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reviews WHERE strpos(image_urls::text, ?) > 0)", Boolean.class, publicId));
    }
}
