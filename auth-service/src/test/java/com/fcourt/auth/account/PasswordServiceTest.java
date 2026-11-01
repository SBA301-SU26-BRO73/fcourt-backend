package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.account.AccountRepository.AccountRecord;
import com.fcourt.auth.account.PasswordResetRepository.ResetToken;
import com.fcourt.auth.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final String CURRENT = "Current-pass1";
    static final String TOKEN = "emailed-reset-token";
    static final PasswordEncoder HASHER = new BCryptPasswordEncoder(4);

    AccountRepository accounts;
    PasswordResetRepository resets;
    SessionService sessions;
    OutboxWriter outbox;
    PasswordService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        resets = mock(PasswordResetRepository.class);
        sessions = mock(SessionService.class);
        outbox = mock(OutboxWriter.class);
        service = new PasswordService(accounts, resets, sessions, outbox, HASHER,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));
    }

    @Test
    void forgotStoresOnlyTheTokenHashAndPublishesTheTokenInTheEvent() {
        when(accounts.findByEmail("owner@demo.com")).thenReturn(Optional.of(account(7, "ACTIVE")));

        String message = service.forgot("  Owner@Demo.com ");

        assertThat(message).isEqualTo(PasswordService.FORGOT_MESSAGE);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(resets).invalidateOpenTokens(7, NOW);
        verify(resets).insert(eq(7L), hash.capture(), eq(NOW.plus(Duration.ofMinutes(30))), eq(NOW));
        var event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(7L), eq("PasswordResetRequestedEvent"), eq(1), event.capture());
        var data = (PasswordResetRequestedEvent) event.getValue();
        assertThat(data.userId()).isEqualTo(7);
        assertThat(data.email()).isEqualTo("owner@demo.com");
        assertThat(data.fullName()).isEqualTo("Full Name");
        assertThat(data.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(data.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(hash.getValue()).isEqualTo(SessionService.hash(data.token())).isNotEqualTo(data.token());
    }

    @Test
    void forgotGivesTheSameAnswerForUnknownEmailsAndWritesNothing() {
        when(accounts.findByEmail("ghost@demo.com")).thenReturn(Optional.empty());

        assertThat(service.forgot("ghost@demo.com")).isEqualTo(PasswordService.FORGOT_MESSAGE);

        verifyNoInteractions(resets, outbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_APPROVAL", "REJECTED", "LOCKED", "INACTIVE"})
    void forgotIgnoresAccountsThatAreNotActive(String status) {
        when(accounts.findByEmail("owner@demo.com")).thenReturn(Optional.of(account(7, status)));

        assertThat(service.forgot("owner@demo.com")).isEqualTo(PasswordService.FORGOT_MESSAGE);

        verifyNoInteractions(resets, outbox);
    }

    @Test
    void forgotHonoursOnlyOneRequestPerCooldownWindow() {
        when(accounts.findByEmail("owner@demo.com")).thenReturn(Optional.of(account(7, "ACTIVE")));
        when(resets.requestedSince(7, NOW.minus(PasswordService.REQUEST_COOLDOWN))).thenReturn(true);

        assertThat(service.forgot("owner@demo.com")).isEqualTo(PasswordService.FORGOT_MESSAGE);

        verify(resets, never()).insert(anyLong(), anyString(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void resetSetsTheNewPasswordSpendsTheTokenAndRevokesEverySession() {
        when(resets.findForUpdate(SessionService.hash(TOKEN)))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "ACTIVE")));

        service.reset(TOKEN, "Brand-new-pass1");

        var hash = ArgumentCaptor.forClass(String.class);
        var order = inOrder(resets, accounts, sessions);
        order.verify(resets).markUsed(11, NOW);
        order.verify(accounts).updatePassword(eq(7L), hash.capture(), eq(NOW));
        order.verify(sessions).closeAll(7);
        assertThat(HASHER.matches("Brand-new-pass1", hash.getValue())).isTrue();
    }

    @Test
    void resetRejectsUnknownUsedAndExpiredTokensWithOneMessage() {
        when(resets.findForUpdate(SessionService.hash("unknown"))).thenReturn(Optional.empty());
        when(resets.findForUpdate(SessionService.hash("used")))
                .thenReturn(Optional.of(token(NOW.minusSeconds(5), NOW.plusSeconds(60))));
        when(resets.findForUpdate(SessionService.hash("expired")))
                .thenReturn(Optional.of(token(null, NOW.minusSeconds(1))));

        for (String presented : new String[] {"unknown", "used", "expired"}) {
            assertThatThrownBy(() -> service.reset(presented, "Brand-new-pass1"))
                    .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.BAD_REQUEST,
                            PasswordService.INVALID_TOKEN));
        }

        verify(accounts, never()).updatePassword(anyLong(), anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void resetForAnAccountThatNoLongerExistsLooksLikeAnInvalidToken() {
        when(resets.findForUpdate(SessionService.hash(TOKEN)))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(TOKEN, "Brand-new-pass1"))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.BAD_REQUEST,
                        PasswordService.INVALID_TOKEN));
    }

    @Test
    void resetForALockedAccountIsRefusedAndChangesNothing() {
        when(resets.findForUpdate(SessionService.hash(TOKEN)))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "LOCKED")));

        assertThatThrownBy(() -> service.reset(TOKEN, "Brand-new-pass1"))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.FORBIDDEN, "Account is locked"));

        verify(resets, never()).markUsed(anyLong(), any());
        verify(accounts, never()).updatePassword(anyLong(), anyString(), any());
    }

    @Test
    void changeRevokesAllSessionsAndHandsBackAFreshOne() {
        var fresh = new AuthResponse("access", "refresh", "Bearer", 900, null);
        var account = account(7, "ACTIVE");
        when(accounts.findById(7)).thenReturn(Optional.of(account));
        when(sessions.open(account)).thenReturn(fresh);

        var response = service.change(7, CURRENT, "Brand-new-pass1");

        assertThat(response).isSameAs(fresh);
        var hash = ArgumentCaptor.forClass(String.class);
        var order = inOrder(accounts, sessions);
        order.verify(accounts).updatePassword(eq(7L), hash.capture(), eq(NOW));
        order.verify(sessions).closeAll(7);
        order.verify(sessions).open(account);
        assertThat(HASHER.matches("Brand-new-pass1", hash.getValue())).isTrue();
    }

    @Test
    void changeNeedsTheRightCurrentPassword() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "ACTIVE")));

        assertThatThrownBy(() -> service.change(7, "wrong-password", "Brand-new-pass1"))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.BAD_REQUEST,
                        "Current password is incorrect"));

        verify(accounts, never()).updatePassword(anyLong(), anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void changeRejectsReusingTheCurrentPassword() {
        when(accounts.findById(7)).thenReturn(Optional.of(account(7, "ACTIVE")));

        assertThatThrownBy(() -> service.change(7, CURRENT, CURRENT))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.BAD_REQUEST,
                        "New password must differ from the current password"));

        verifyNoInteractions(sessions);
    }

    @Test
    void changeRefusesMissingAndLockedAccounts() {
        when(accounts.findById(1)).thenReturn(Optional.empty());
        when(accounts.findById(2)).thenReturn(Optional.of(account(2, "LOCKED")));

        assertThatThrownBy(() -> service.change(1, CURRENT, "Brand-new-pass1"))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.NOT_FOUND, "Account not found"));
        assertThatThrownBy(() -> service.change(2, CURRENT, "Brand-new-pass1"))
                .satisfies(error -> AuthServiceTest.assertApi(error, HttpStatus.FORBIDDEN, "Account is locked"));
        verifyNoInteractions(sessions);
    }

    static ResetToken token(Instant usedAt, Instant expiresAt) {
        return new ResetToken(11, 7, expiresAt.atOffset(ZoneOffset.UTC),
                usedAt == null ? null : usedAt.atOffset(ZoneOffset.UTC));
    }

    static AccountRecord account(long id, String status) {
        return new AccountRecord(id, "owner@demo.com", HASHER.encode(CURRENT), "Full Name", "0909000001",
                "ADMIN", status, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
