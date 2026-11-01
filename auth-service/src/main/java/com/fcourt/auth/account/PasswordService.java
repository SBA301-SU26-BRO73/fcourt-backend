package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.outbox.OutboxWriter;
import com.fcourt.auth.user.UserStatus;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot, reset and change password. A reset token works once and only its hash is stored. Every way
 * of changing a password revokes refresh tokens, so a stolen session does not survive the change.
 */
@Service
public class PasswordService {
    static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(60);
    static final String FORGOT_MESSAGE = "If the email is registered, a password reset link has been sent";
    static final String INVALID_TOKEN = "Reset token is invalid or has expired";
    private static final int TOKEN_BYTES = 32;

    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final SessionService sessions;
    private final OutboxWriter outbox;
    private final PasswordEncoder passwordEncoder;
    private final FailedAttemptThrottle throttle;
    private final Clock clock;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();

    PasswordService(AccountRepository accounts, PasswordResetRepository resets, SessionService sessions,
                    OutboxWriter outbox, PasswordEncoder passwordEncoder, FailedAttemptThrottle throttle, Clock clock,
                    @Value("${auth.password-reset.ttl:30m}") Duration tokenTtl) {
        this.accounts = accounts;
        this.resets = resets;
        this.sessions = sessions;
        this.outbox = outbox;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Always succeeds with the same message so the endpoint cannot be used to find registered emails.
     * Only ACTIVE accounts get a token, and one request per minute per account is honoured.
     */
    @Transactional
    public String forgot(String rawEmail) {
        Instant now = clock.instant();
        accounts.findByEmail(rawEmail.strip().toLowerCase(Locale.ROOT))
                .filter(account -> UserStatus.ACTIVE.name().equals(account.status()))
                .filter(account -> !resets.requestedSince(account.id(), now.minus(REQUEST_COOLDOWN)))
                .ifPresent(account -> issueToken(account, now));
        return FORGOT_MESSAGE;
    }

    @Transactional
    public void reset(String token, String newPassword) {
        Instant now = clock.instant();
        var stored = resets.findForUpdate(SessionService.hash(token))
                .filter(found -> found.usedAt() == null && found.expiresAt().toInstant().isAfter(now))
                .orElseThrow(() -> ApiException.badRequest(INVALID_TOKEN));
        var account = accounts.findById(stored.userId()).orElseThrow(() -> ApiException.badRequest(INVALID_TOKEN));
        AccountPolicy.requireActive(account);

        resets.markUsed(stored.id(), now);
        resets.invalidateOpenTokens(account.id(), now);
        accounts.updatePassword(account.id(), passwordEncoder.encode(newPassword), now);
        sessions.closeAll(account.id());
    }

    /**
     * Needs the current password. Every session is revoked and a fresh one is returned, so the caller
     * stays signed in on this device while all other devices have to sign in again. Wrong current passwords
     * are throttled per user, so a stolen access token cannot be used to guess the password.
     */
    @Transactional
    public AuthResponse change(long userId, String currentPassword, String newPassword) {
        String attemptKey = FailedAttemptThrottle.passwordChangeKey(userId);
        throttle.checkAllowed(attemptKey);
        var account = accounts.findById(userId).orElseThrow(() -> ApiException.notFound("Account not found"));
        AccountPolicy.requireActive(account);
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throttle.recordFailure(attemptKey);
            throw ApiException.badRequest("Current password is incorrect");
        }
        throttle.recordSuccess(attemptKey);
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw ApiException.badRequest("New password must differ from the current password");
        }
        accounts.updatePassword(userId, passwordEncoder.encode(newPassword), clock.instant());
        sessions.closeAll(userId);
        return sessions.open(account);
    }

    private void issueToken(AccountRecord account, Instant now) {
        String token = newToken();
        Instant expiresAt = now.plus(tokenTtl);
        resets.invalidateOpenTokens(account.id(), now);
        resets.insert(account.id(), SessionService.hash(token), expiresAt, now);
        outbox.append(account.id(), PasswordResetRequestedEvent.TYPE, PasswordResetRequestedEvent.VERSION,
                new PasswordResetRequestedEvent(account.id(), account.email(), account.fullName(), token, expiresAt));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
