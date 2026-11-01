package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.auth.account.FailedAttemptRepository.Attempts;
import com.fcourt.auth.common.RetryLaterException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class FailedAttemptThrottleTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final String KEY = "login:customer@demo.com";

    FailedAttemptRepository repository;
    FailedAttemptThrottle throttle;

    @BeforeEach
    void setUp() {
        repository = mock(FailedAttemptRepository.class);
        throttle = new FailedAttemptThrottle(repository, Clock.fixed(NOW, ZoneOffset.UTC), 5,
                Duration.ofMinutes(15), Duration.ofMinutes(15));
    }

    @Test
    void aLockedKeyIsRefusedWith429AndTheTimeLeft() {
        when(repository.lockedUntil(KEY)).thenReturn(Optional.of(NOW.plusSeconds(600)));

        assertThatThrownBy(() -> throttle.checkAllowed(KEY))
                .isInstanceOfSatisfying(RetryLaterException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getMessage()).isEqualTo(FailedAttemptThrottle.LOCKED);
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(600));
                });
    }

    @Test
    void anUnknownKeyOrAnExpiredLockIsAllowed() {
        when(repository.lockedUntil(KEY)).thenReturn(Optional.empty());
        assertThatCode(() -> throttle.checkAllowed(KEY)).doesNotThrowAnyException();

        when(repository.lockedUntil(KEY)).thenReturn(Optional.of(NOW));
        assertThatCode(() -> throttle.checkAllowed(KEY)).doesNotThrowAnyException();
    }

    @Test
    void failuresInsideTheWindowAreCountedWithoutLockingBelowTheLimit() {
        when(repository.lockOrCreate(KEY, NOW)).thenReturn(new Attempts(3, NOW.minusSeconds(60), null));

        throttle.recordFailure(KEY);

        verify(repository).save(KEY, new Attempts(4, NOW.minusSeconds(60), null));
    }

    @Test
    void theFailureThatReachesTheLimitLocksTheKey() {
        when(repository.lockOrCreate(KEY, NOW)).thenReturn(new Attempts(4, NOW.minusSeconds(60), null));

        throttle.recordFailure(KEY);

        verify(repository).save(KEY, new Attempts(5, NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(15))));
    }

    @Test
    void anOldWindowStartsCountingAgain() {
        when(repository.lockOrCreate(KEY, NOW)).thenReturn(new Attempts(4, NOW.minus(Duration.ofMinutes(16)), null));

        throttle.recordFailure(KEY);

        verify(repository).save(KEY, new Attempts(1, NOW, null));
    }

    @Test
    void anExpiredLockStartsCountingAgain() {
        when(repository.lockOrCreate(KEY, NOW))
                .thenReturn(new Attempts(5, NOW.minus(Duration.ofMinutes(5)), NOW.minusSeconds(1)));

        throttle.recordFailure(KEY);

        verify(repository).save(KEY, new Attempts(1, NOW, null));
    }

    @Test
    void successClearsTheCount() {
        throttle.recordSuccess(KEY);

        verify(repository).delete(KEY);
    }

    /** The caller throws right after recording, so the count must commit in its own transaction. */
    @Test
    void failuresAreRecordedInTheirOwnTransaction() throws NoSuchMethodException {
        var transactional = FailedAttemptThrottle.class.getMethod("recordFailure", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
