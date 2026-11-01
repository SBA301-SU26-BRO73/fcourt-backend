package com.fcourt.auth.account;

import com.fcourt.auth.account.FailedAttemptRepository.Attempts;
import com.fcourt.auth.common.RetryLaterException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slows down password guessing: after {@code maxFailures} wrong passwords for one key within
 * {@code window}, the key is locked for {@code lockDuration} and every check answers 429, even with the
 * right password. The lock is temporary on purpose, so a stranger can only delay the owner, not lock
 * them out for good.
 */
@Service
public class FailedAttemptThrottle {
    static final String LOCKED = "Too many failed attempts. Please try again later";

    private final FailedAttemptRepository attempts;
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Duration lockDuration;

    FailedAttemptThrottle(FailedAttemptRepository attempts, Clock clock,
                          @Value("${auth.attempts.max-failures:5}") int maxFailures,
                          @Value("${auth.attempts.window:15m}") Duration window,
                          @Value("${auth.attempts.lock:15m}") Duration lockDuration) {
        this.attempts = attempts;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockDuration = lockDuration;
    }

    static String loginKey(String normalisedEmail) {
        return "login:" + normalisedEmail;
    }

    static String passwordChangeKey(long userId) {
        return "password-change:" + userId;
    }

    /** Throws 429 while the key is locked. Call before checking the password. */
    public void checkAllowed(String key) {
        Instant now = clock.instant();
        attempts.lockedUntil(key).filter(until -> until.isAfter(now)).ifPresent(until -> {
            throw new RetryLaterException(LOCKED, Duration.between(now, until));
        });
    }

    /**
     * Runs in its own transaction: the caller is about to throw (401/400), which would otherwise roll the
     * count back and make the throttle useless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String key) {
        Instant now = clock.instant();
        var current = attempts.lockOrCreate(key, now);
        boolean lockOver = current.lockedUntil() != null && !current.lockedUntil().isAfter(now);
        boolean windowOver = !current.windowStartedAt().isAfter(now.minus(window));
        var counted = lockOver || windowOver ? new Attempts(0, now, null) : current;
        int failures = counted.failures() + 1;
        Instant lockedUntil = failures >= maxFailures ? now.plus(lockDuration) : null;
        attempts.save(key, new Attempts(failures, counted.windowStartedAt(), lockedUntil));
    }

    /** The right password clears the count. */
    public void recordSuccess(String key) {
        attempts.delete(key);
    }
}
