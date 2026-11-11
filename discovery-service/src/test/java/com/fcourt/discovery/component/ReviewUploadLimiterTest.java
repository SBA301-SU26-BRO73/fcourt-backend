package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReviewUploadLimiterTest {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T00:00:00Z"));
    final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void capsFilesPerUserPerWindowAndResetsAfterTheWindow() {
        var limiter = new ReviewUploadLimiter(5, 600, clock);
        limiter.acquire(1, 3);
        limiter.acquire(1, 2);
        assertThatThrownBy(() -> limiter.acquire(1, 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
        assertThatCode(() -> limiter.acquire(2, 5)).as("other users are independent").doesNotThrowAnyException();
        now.set(now.get().plusSeconds(600));
        assertThatCode(() -> limiter.acquire(1, 5)).as("new window").doesNotThrowAnyException();
    }

    @Test
    void aRejectedRequestIsNotPartiallyCharged() {
        var limiter = new ReviewUploadLimiter(5, 600, clock);
        limiter.acquire(1, 4);
        assertThatThrownBy(() -> limiter.acquire(1, 2)).isInstanceOf(ResponseStatusException.class);
        assertThatCode(() -> limiter.acquire(1, 1)).doesNotThrowAnyException();
    }

    @Test
    void expiredWindowsArePurgedSoMemoryStaysBounded() {
        var limiter = new ReviewUploadLimiter(5, 600, clock);
        for (long user = 1; user <= ReviewUploadLimiter.PURGE_AT; user++) limiter.acquire(user, 1);
        assertThat(limiter.tracked()).isEqualTo(ReviewUploadLimiter.PURGE_AT);
        now.set(now.get().plusSeconds(601));
        limiter.acquire(999_999, 1);
        assertThat(limiter.tracked()).isEqualTo(1);
    }
}
