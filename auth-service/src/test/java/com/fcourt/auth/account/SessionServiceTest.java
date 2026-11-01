package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.account.RefreshTokenRepository.StoredToken;
import com.fcourt.auth.common.ApiException;
import com.fcourt.auth.token.AccessTokenIssuer;
import com.fcourt.auth.token.AccessTokenIssuer.IssuedToken;
import com.fcourt.auth.token.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

class SessionServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final String PRESENTED = "presented-refresh-token";

    AccessTokenIssuer accessTokens;
    RefreshTokenRepository refreshTokens;
    AccountRepository accounts;
    SessionService sessions;

    @BeforeEach
    void setUp() {
        accessTokens = mock(AccessTokenIssuer.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        accounts = mock(AccountRepository.class);
        var properties = new JwtProperties("http://issuer.test", "fcourt", null, null,
                Duration.ofMinutes(15), Duration.ofDays(30));
        sessions = new SessionService(accessTokens, refreshTokens, accounts, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(accessTokens.issue(anyLong(), anyString())).thenReturn(new IssuedToken("access-jwt", 900));
    }

    @Test
    void openIssuesABearerPairAndStoresOnlyTheHashOfTheRefreshToken() {
        var response = sessions.open(account(7, "CUSTOMER", "ACTIVE"));

        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user().id()).isEqualTo(7);
        assertThat(response.refreshToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        var stored = ArgumentCaptor.forClass(String.class);
        verify(refreshTokens).insert(eq(7L), stored.capture(), eq(NOW.plus(Duration.ofDays(30))), eq(NOW));
        assertThat(stored.getValue()).isEqualTo(SessionService.hash(response.refreshToken()))
                .isNotEqualTo(response.refreshToken()).hasSize(64);
        verify(accessTokens).issue(7, "CUSTOMER");
    }

    @Test
    void everySessionGetsADifferentRefreshToken() {
        var first = sessions.open(account(7, "CUSTOMER", "ACTIVE"));
        var second = sessions.open(account(7, "CUSTOMER", "ACTIVE"));

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    void rotateRevokesTheUsedTokenAndIssuesANewPair() {
        when(refreshTokens.findForUpdate(SessionService.hash(PRESENTED)))
                .thenReturn(Optional.of(stored(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "CUSTOMER", "ACTIVE")));

        var response = sessions.rotate(PRESENTED);

        assertThat(response.refreshToken()).isNotEqualTo(PRESENTED);
        var order = inOrder(refreshTokens);
        order.verify(refreshTokens).revoke(11, NOW);
        order.verify(refreshTokens).insert(eq(7L), anyString(), any(), eq(NOW));
        verify(refreshTokens, never()).revokeAllOf(anyLong(), any());
    }

    @Test
    void rotateRejectsATokenItNeverIssued() {
        when(refreshTokens.findForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessions.rotate(PRESENTED))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Refresh token is invalid"));
        verify(refreshTokens, never()).insert(anyLong(), anyString(), any(), any());
    }

    @Test
    void reusingARevokedTokenRevokesEverySessionOfThatUser() {
        when(refreshTokens.findForUpdate(SessionService.hash(PRESENTED)))
                .thenReturn(Optional.of(stored(NOW.minusSeconds(5), NOW.plusSeconds(60))));

        assertThatThrownBy(() -> sessions.rotate(PRESENTED))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Refresh token is invalid"));

        verify(refreshTokens).revokeAllOf(7, NOW);
        verify(refreshTokens, never()).insert(anyLong(), anyString(), any(), any());
    }

    @Test
    void rotateRejectsAnExpiredToken() {
        when(refreshTokens.findForUpdate(SessionService.hash(PRESENTED)))
                .thenReturn(Optional.of(stored(null, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> sessions.rotate(PRESENTED))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Refresh token has expired"));
        verify(refreshTokens, never()).insert(anyLong(), anyString(), any(), any());
    }

    @Test
    void rotateForALockedAccountRevokesItsSessionsAndRefuses() {
        when(refreshTokens.findForUpdate(SessionService.hash(PRESENTED)))
                .thenReturn(Optional.of(stored(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "CUSTOMER", "LOCKED")));

        assertThatThrownBy(() -> sessions.rotate(PRESENTED))
                .satisfies(error -> assertApi(error, HttpStatus.FORBIDDEN, "Account is locked"));

        verify(refreshTokens).revokeAllOf(7, NOW);
        verify(refreshTokens, never()).insert(anyLong(), anyString(), any(), any());
    }

    @Test
    void rotateForAnAccountThatNoLongerExistsIsUnauthorized() {
        when(refreshTokens.findForUpdate(SessionService.hash(PRESENTED)))
                .thenReturn(Optional.of(stored(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessions.rotate(PRESENTED))
                .satisfies(error -> assertApi(error, HttpStatus.UNAUTHORIZED, "Refresh token is invalid"));
    }

    @Test
    void closeRevokesTheTokenByItsHashAndCloseAllRevokesTheUser() {
        sessions.close(PRESENTED);
        sessions.closeAll(7);

        verify(refreshTokens).revokeByHash(SessionService.hash(PRESENTED), NOW);
        verify(refreshTokens).revokeAllOf(7, NOW);
    }

    /**
     * The reuse and not-active branches write (revoke all) and then throw. If that rolled back the
     * revocation would be lost, so the exception must not roll the transaction back.
     */
    @Test
    void rotateCommitsItsRevocationsEvenThoughItThrows() throws NoSuchMethodException {
        var transactional = SessionService.class.getMethod("rotate", String.class).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(ApiException.class);
    }

    static void assertApi(Throwable error, HttpStatus status, String detail) {
        AuthServiceTest.assertApi(error, status, detail);
    }

    static StoredToken stored(Instant revokedAt, Instant expiresAt) {
        return new StoredToken(11, 7, expiresAt.atOffset(ZoneOffset.UTC),
                revokedAt == null ? null : revokedAt.atOffset(ZoneOffset.UTC));
    }

    static AccountRecord account(long id, String role, String status) {
        return new AccountRecord(id, "user@demo.com", "hash", "Full Name", "0909000001", role, status,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
