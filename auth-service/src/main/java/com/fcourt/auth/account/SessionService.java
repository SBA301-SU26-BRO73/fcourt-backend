package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.token.AccessTokenIssuer;
import com.fcourt.auth.token.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access token plus opaque refresh token pairs. A refresh token works once: it is revoked when
 * exchanged and replaced by a new one. Presenting an already rotated token means it leaked or was
 * replayed, so every session of that user is revoked. A token revoked by logout, a password change or
 * a locked account is only refused.
 */
@Service
public class SessionService {
    private static final int TOKEN_BYTES = 32;
    private static final String BEARER = "Bearer";

    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenRepository refreshTokens;
    private final AccountRepository accounts;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    SessionService(AccessTokenIssuer accessTokens, RefreshTokenRepository refreshTokens, AccountRepository accounts,
                   JwtProperties properties, Clock clock) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse open(AccountRecord account) {
        Instant now = clock.instant();
        String refreshToken = newToken();
        refreshTokens.insert(account.id(), hash(refreshToken), now.plus(properties.refreshTtl()), now);
        var access = accessTokens.issue(account.id(), account.role());
        return new AuthResponse(access.value(), refreshToken, BEARER, access.expiresInSeconds(),
                AuthService.toResponse(account));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse rotate(String refreshToken) {
        Instant now = clock.instant();
        var stored = refreshTokens.findForUpdate(hash(refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("Refresh token is invalid"));
        if (stored.rotatedAt() != null) {
            // Committed despite the exception (noRollbackFor), so the stolen token family dies.
            refreshTokens.revokeAllOf(stored.userId(), now);
            throw ApiException.unauthorized("Refresh token is invalid");
        }
        if (stored.revokedAt() != null) {
            // Logged out or revoked (password change, lock): dead, but not evidence of theft. Revoking
            // everything here would let a stale device sign out the session that replaced it.
            throw ApiException.unauthorized("Refresh token is invalid");
        }
        if (!stored.expiresAt().toInstant().isAfter(now)) {
            throw ApiException.unauthorized("Refresh token has expired");
        }
        var account = accounts.findById(stored.userId())
                .orElseThrow(() -> ApiException.unauthorized("Refresh token is invalid"));
        try {
            AccountPolicy.requireActive(account);
        } catch (ApiException notActive) {
            refreshTokens.revokeAllOf(account.id(), now);
            throw notActive;
        }
        refreshTokens.markRotated(stored.id(), now);
        return open(account);
    }

    @Transactional
    public void close(String refreshToken) {
        refreshTokens.revokeByHash(hash(refreshToken), clock.instant());
    }

    @Transactional
    public void closeAll(long userId) {
        refreshTokens.revokeAllOf(userId, clock.instant());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
