package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewImageCleanupTest {
    static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Fake host (any provider): serves objects, honours olderThan like a real adapter, records deletes. */
    static class FakeHost implements ImageStorage {
        final List<StoredObject> objects;
        final List<String> deleted = new ArrayList<>();
        final List<String> listedPrefixes = new ArrayList<>();
        String failing;
        FakeHost(List<StoredObject> objects) { this.objects = objects; }
        @Override public String upload(String key, String contentType, byte[] content) { throw new UnsupportedOperationException(); }
        @Override public String publicBaseUrl() { return "https://cdn.example.test/"; }
        @Override public boolean supportsCleanup() { return true; }
        @Override public List<StoredObject> listByPrefix(String prefix, Instant olderThan, int max) {
            listedPrefixes.add(prefix);
            return objects.stream().filter(o -> o.createdAt().isBefore(olderThan)).limit(max).toList();
        }
        @Override public void delete(String key) {
            if (key.equals(failing)) throw new IllegalStateException("boom");
            deleted.add(key);
        }
    }

    static ImageStorage.StoredObject object(String key, long ageHours) {
        return new ImageStorage.StoredObject(key, NOW.minusSeconds(ageHours * 3600));
    }

    ReviewImageCleanup cleanup(FakeHost host, Set<String> referenced, boolean dryRunConfig) {
        var jdbc = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                return (T) Boolean.valueOf(referenced.contains((String) args[0]));
            }
        };
        return new ReviewImageCleanup(host, jdbc, clock, 24, true, dryRunConfig);
    }

    @Test
    void deletesOnlyOldUnreferencedPhotos() {
        var host = new FakeHost(List.of(object("reviews/1/old-orphan", 48), object("reviews/1/new-orphan", 2),
                object("reviews/1/attached", 72), object("reviews/2/old-orphan", 25), object("branches/9/other-key-space", 99)));
        var result = cleanup(host, Set.of("reviews/1/attached"), false).run(false);
        assertThat(host.deleted).containsExactly("reviews/1/old-orphan", "reviews/2/old-orphan");
        assertThat(result).isEqualTo(new ReviewImageCleanup.Result(4, 1, 2, 0, 0));
        assertThat(host.listedPrefixes).containsOnly("reviews/");
    }

    @Test
    void dryRunDeletesNothingButCountsCandidates() {
        var host = new FakeHost(List.of(object("reviews/1/a", 48), object("reviews/1/b", 48)));
        var result = cleanup(host, Set.of(), true).run(true);
        assertThat(host.deleted).isEmpty();
        assertThat(result.wouldDelete()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
    }

    @Test
    void aFailedDeleteIsCountedAndDoesNotStopTheRunAndRerunsAreIdempotent() {
        var host = new FakeHost(List.of(object("reviews/1/a", 48), object("reviews/1/b", 48)));
        host.failing = "reviews/1/a";
        var job = cleanup(host, Set.of(), false);
        assertThat(job.run(false).failed()).isEqualTo(1);
        assertThat(host.deleted).containsExactly("reviews/1/b");
        host.failing = null;
        job.run(false);
        assertThat(host.deleted).contains("reviews/1/a");
    }

    @Test
    void shippedConfigDefaultsToDryRunSoNothingIsDeletedUntilExplicitlyEnabled() {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("discovery.reviews.images.cleanup.dry-run"))
                .isEqualTo("${DISCOVERY_REVIEW_CLEANUP_DRY_RUN:true}");
    }

    @Test
    void scheduledRunWithDryRunConfiguredDeletesNothing() {
        var host = new FakeHost(List.of(object("reviews/1/a", 48)));
        cleanup(host, Set.of(), true).scheduled();
        assertThat(host.listedPrefixes).hasSize(1);
        assertThat(host.deleted).isEmpty();
    }

    @Test
    void scheduledRunDeletesOnlyWhenDryRunIsExplicitlyFalse() {
        var host = new FakeHost(List.of(object("reviews/1/a", 48)));
        cleanup(host, Set.of(), false).scheduled();
        assertThat(host.deleted).containsExactly("reviews/1/a");
    }

    @Test
    void scheduledRunDoesNothingWhenStorageHasNoCleanupSupport() {
        var host = new FakeHost(List.of(object("reviews/1/a", 48))) {
            @Override public boolean supportsCleanup() { return false; }
        };
        cleanup(host, Set.of(), false).scheduled();
        assertThat(host.deleted).isEmpty();
        assertThat(host.listedPrefixes).isEmpty();
    }
}
