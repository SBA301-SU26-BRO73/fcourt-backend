package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class GoogleLoginServiceTest {
    private final NimbusJwtDecoder decoder = mock(NimbusJwtDecoder.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private final GoogleLoginService service = new GoogleLoginService(
            "web-client-id", decoder, accounts, sessions, passwords, jdbc, clock);

    @Test
    void rejectsInvalidGoogleTokenBeforeDatabaseAccess() {
        when(decoder.decode("bad")).thenThrow(new JwtException("expired"));
        assertThatThrownBy(() -> service.login("bad")).hasMessageContaining("invalid or expired");
        verifyNoInteractions(accounts, sessions, jdbc);
    }

    @Test
    void rejectsUnverifiedEmail() {
        when(decoder.decode("token")).thenReturn(token("player@example.com", false));
        assertThatThrownBy(() -> service.login("token")).hasMessageContaining("verified identity");
        verifyNoInteractions(accounts, sessions, jdbc);
    }

    @Test
    void doesNotTakeOverPasswordAccountAtThirdPartyEmail() {
        when(decoder.decode("token")).thenReturn(token("player@example.com", true));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("google-sub"))).thenReturn(List.of());
        when(accounts.findByEmail("player@example.com")).thenReturn(Optional.of(
                new AccountRepository.AccountRecord(42, "player@example.com", "hash", "Player", null,
                        "CUSTOMER", "ACTIVE", null)));
        assertThatThrownBy(() -> service.login("token")).hasMessageContaining("account linking");
        verifyNoInteractions(sessions);
    }

    private Jwt token(String email, boolean verified) {
        return Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://accounts.google.com").audience(List.of("web-client-id"))
                .subject("google-sub").issuedAt(clock.instant())
                .expiresAt(clock.instant().plusSeconds(3600))
                .claim("email", email).claim("email_verified", verified).build();
    }
}
