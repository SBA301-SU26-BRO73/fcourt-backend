package com.fcourt.auth.account;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Refuses access tokens issued before the subject's last password change or reset, so changing the
 * password also ends access tokens stolen earlier instead of leaving them valid until they expire.
 * {@code iat} has whole-second precision, so the change time is truncated to the second: the token
 * handed back by the change itself stays valid.
 * Costs one primary key lookup per authenticated request to this service.
 */
@Component
public class PasswordChangeTokenValidator implements OAuth2TokenValidator<Jwt> {
    static final OAuth2Error ISSUED_BEFORE_PASSWORD_CHANGE = new OAuth2Error("invalid_token",
            "The access token was issued before the password was changed", null);

    private final AccountRepository accounts;

    PasswordChangeTokenValidator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException notAUser) {
            // The subject check in SecurityConfiguration rejects these tokens already.
            return OAuth2TokenValidatorResult.success();
        }
        try {
            boolean stale = accounts.passwordChangedAt(userId)
                    .map(changedAt -> issuedAt == null || issuedAt.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS)))
                    .orElse(false);
            return stale ? OAuth2TokenValidatorResult.failure(ISSUED_BEFORE_PASSWORD_CHANGE)
                    : OAuth2TokenValidatorResult.success();
        } catch (DataAccessException unavailable) {
            // Fail closed: without the database the token cannot be shown to be current.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
    }
}
