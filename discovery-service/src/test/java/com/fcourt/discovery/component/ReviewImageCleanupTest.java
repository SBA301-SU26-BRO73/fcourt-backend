package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /** Fake host: two pages of assets, records deletes; a delete of {@code failing} throws. */
    static class FakeHost implements ReviewImageStorage {
        final List<List<StoredAsset>> pages;
        final List<String> deleted = new ArrayList<>();
        final List<String> prefixes = new ArrayList<>();
        String failing;
        FakeHost(List<List<StoredAsset>> pages) { this.pages = pages; }
        @Override public String store(String folder, Upload upload) { throw new UnsupportedOperationException(); }
        @Override public boolean supportsCleanup() { return true; }
        @Override public AssetPage list(String prefix, String cursor) {
            prefixes.add(prefix);
            int index = cursor == null ? 0 : Integer.parseInt(cursor);
            return new AssetPage(pages.get(index), index + 1 < pages.size() ? Integer.toString(index + 1) : null);
        }
        @Override public void delete(String publicId) {
            if (publicId.equals(failing)) throw new IllegalStateException("boom");
            deleted.add(publicId);
        }
    }

    static ReviewImageStorage.StoredAsset asset(String id, long ageHours) {
        return new ReviewImageStorage.StoredAsset(id, "https://res.cloudinary.com/demo/image/upload/v1/" + id + ".jpg", NOW.minusSeconds(ageHours * 3600));
    }

    ReviewImageCleanup cleanup(FakeHost host, Set<String> referenced) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenAnswer(call -> referenced.contains((String) call.getArgument(2)));
        return new ReviewImageCleanup(host, jdbc, clock, 24, true, false);
    }

    @Test
    void deletesOnlyOldUnreferencedPhotosAcrossPages() {
        var host = new FakeHost(List.of(
                List.of(asset("fcourt/reviews/1/old-orphan", 48), asset("fcourt/reviews/1/new-orphan", 2), asset("fcourt/reviews/1/attached", 72)),
                List.of(asset("fcourt/reviews/2/old-orphan", 25), asset("fcourt/branches/9/other-folder", 99))));
        var result = cleanup(host, Set.of("fcourt/reviews/1/attached")).run(false);
        assertThat(host.deleted).containsExactly("fcourt/reviews/1/old-orphan", "fcourt/reviews/2/old-orphan");
        assertThat(result).isEqualTo(new ReviewImageCleanup.Result(5, 1, 1, 2, 0, 0));
        assertThat(host.prefixes).containsOnly("fcourt/reviews/");
    }

    @Test
    void dryRunDeletesNothingButCountsCandidates() {
        var host = new FakeHost(List.of(List.of(asset("fcourt/reviews/1/a", 48), asset("fcourt/reviews/1/b", 48))));
        var result = cleanup(host, Set.of()).run(true);
        assertThat(host.deleted).isEmpty();
        assertThat(result.wouldDelete()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
    }

    @Test
    void aFailedDeleteIsCountedAndDoesNotStopTheRunAndRerunsAreIdempotent() {
        var host = new FakeHost(List.of(List.of(asset("fcourt/reviews/1/a", 48), asset("fcourt/reviews/1/b", 48))));
        host.failing = "fcourt/reviews/1/a";
        var job = cleanup(host, Set.of());
        var first = job.run(false);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(host.deleted).containsExactly("fcourt/reviews/1/b");
        host.failing = null;
        job.run(false);
        assertThat(host.deleted).contains("fcourt/reviews/1/a");
    }

    @Test
    void scheduledRunDoesNothingWhenStorageHasNoCleanupSupport() {
        var host = new FakeHost(List.of(List.of(asset("fcourt/reviews/1/a", 48)))) {
            @Override public boolean supportsCleanup() { return false; }
        };
        cleanup(host, Set.of()).scheduled();
        assertThat(host.deleted).isEmpty();
        assertThat(host.prefixes).isEmpty();
    }
}
