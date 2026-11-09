package com.fcourt.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void blocksAfterLimitAndRefillsOverTime() {
        var clock = new MutableClock();
        var limiter = new TokenBucketLimiter(5, Duration.ofMinutes(15), 100, clock);
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("k")).isZero();
        long wait = limiter.tryAcquire("k");
        assertThat(wait).isBetween(1L, 180L);
        assertThat(limiter.tryAcquire("other")).isZero();
        clock.now = clock.now.plusSeconds(wait);
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isPositive();
    }

    @Test
    void trackedKeysAreBounded() {
        var clock = new MutableClock();
        var limiter = new TokenBucketLimiter(1, Duration.ofMinutes(15), 10, clock);
        for (int i = 0; i < 1000; i++) limiter.tryAcquire("k" + i);
        assertThat(limiter.tracked()).isLessThanOrEqualTo(10);
    }
}
