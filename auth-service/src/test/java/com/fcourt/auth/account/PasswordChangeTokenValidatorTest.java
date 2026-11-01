package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.oauth2.jwt.Jwt;

class PasswordChangeTokenValidatorTest {
    static final Instant CHANGED = Instant.parse("2026-11-01T10:00:00.700Z");

    AccountRepository accounts;
    PasswordChangeTokenValidator validator;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        validator = new PasswordChangeTokenValidator(accounts);
    }

    @Test
    void aTokenIssuedBeforeThePasswordChangeIsRefused() {
        when(accounts.passwordChangedAt(7)).thenReturn(Optional.of(CHANGED));

        var result = validator.validate(jwt("7", Instant.parse("2026-11-01T09:59:59Z")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).contains(PasswordChangeTokenValidator.ISSUED_BEFORE_PASSWORD_CHANGE);
    }

    /** iat has whole seconds: the token returned by the change itself is issued in the same second. */
    @Test
    void aTokenIssuedInTheSecondOfTheChangeOrLaterIsAccepted() {
        when(accounts.passwordChangedAt(7)).thenReturn(Optional.of(CHANGED));

        assertThat(validator.validate(jwt("7", Instant.parse("2026-11-01T10:00:00Z"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("7", Instant.parse("2026-11-01T10:05:00Z"))).hasErrors()).isFalse();
    }

    @Test
    void aPasswordThatNeverChangedAcceptsTheToken() {
        when(accounts.passwordChangedAt(7)).thenReturn(Optional.empty());

        assertThat(validator.validate(jwt("7", Instant.parse("2020-01-01T00:00:00Z"))).hasErrors()).isFalse();
    }

    @Test
    void aDatabaseFailureRefusesTheToken() {
        when(accounts.passwordChangedAt(7)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(validator.validate(jwt("7", Instant.now())).hasErrors()).isTrue();
    }

    static Jwt jwt(String subject, Instant issuedAt) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).audience(List.of("fcourt"))
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(900)).build();
    }
}
