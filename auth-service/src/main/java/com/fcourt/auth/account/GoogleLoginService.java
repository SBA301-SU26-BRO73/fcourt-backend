package com.fcourt.auth.account;

import com.fcourt.auth.account.AccountDtos.AuthResponse;
import com.fcourt.auth.common.ApiException;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifies Google ID tokens and opens the same FCOURT session used by password login. */
@Service
public class GoogleLoginService {
    private final String clientId;
    private final NimbusJwtDecoder decoder;
    private final AccountRepository accounts;
    private final SessionService sessions;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    GoogleLoginService(@Value("${auth.google.client-id:}") String clientId,
                       @Value("${auth.google.jwk-set-uri}") String jwks,
                       AccountRepository accounts, SessionService sessions, PasswordEncoder passwords,
                       JdbcTemplate jdbc, Clock clock) {
        this(clientId, NimbusJwtDecoder.withJwkSetUri(jwks).build(), accounts, sessions, passwords, jdbc, clock);
    }

    GoogleLoginService(String clientId, NimbusJwtDecoder decoder, AccountRepository accounts,
                       SessionService sessions, PasswordEncoder passwords, JdbcTemplate jdbc, Clock clock) {
        this.clientId = clientId.strip();
        this.decoder = decoder;
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), jwt -> validIssuerAndAudience(jwt)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse login(String idToken) {
        if (clientId.isBlank()) {
            throw new IllegalStateException("AUTH_GOOGLE_CLIENT_ID is required for Google sign-in");
        }
        Jwt token;
        try {
            token = decoder.decode(idToken);
        } catch (JwtException error) {
            throw ApiException.unauthorized("Google ID token is invalid or expired");
        }
        String subject = token.getSubject();
        String email = token.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()
                || !Boolean.TRUE.equals(token.getClaim("email_verified"))) {
            throw ApiException.unauthorized("Google ID token has no verified identity");
        }
        email = email.strip().toLowerCase(Locale.ROOT);
        // Google is authoritative for Gmail and hosted-domain addresses. For other addresses, an
        // existing password account must be linked after its owner proves possession separately.
        boolean authoritative = email.endsWith("@gmail.com") || token.getClaimAsString("hd") != null;
        var linkedIds = jdbc.queryForList("SELECT id FROM users WHERE google_sub = ? AND deleted_at IS NULL",
                Long.class, subject);
        if (!linkedIds.isEmpty()) {
            var account = accounts.findById(linkedIds.getFirst()).orElseThrow();
            AccountPolicy.requireActive(account);
            if (!"CUSTOMER".equals(account.role()))
                throw ApiException.conflict("Google sign-in is only available to customers");
            audit(account.id());
            return sessions.open(account);
        }
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            var account = existing.get();
            AccountPolicy.requireActive(account);
            if (!"CUSTOMER".equals(account.role()) || !authoritative) {
                throw ApiException.conflict("This email needs account linking before Google sign-in");
            }
            int linked = jdbc.update("""
                    UPDATE users SET google_sub = ?, avatar_url = COALESCE(avatar_url, ?), updated_at = ?
                    WHERE id = ? AND (google_sub IS NULL OR google_sub = ?)
                    """, subject, token.getClaimAsString("picture"),
                    java.sql.Timestamp.from(clock.instant()), account.id(), subject);
            if (linked != 1) {
                throw ApiException.conflict("This account is linked to another Google identity");
            }
            audit(account.id());
            return sessions.open(accounts.findById(account.id()).orElseThrow());
        }
        String fullName = token.getClaimAsString("name");
        if (fullName == null || fullName.isBlank()) fullName = email.substring(0, email.indexOf('@'));
        fullName = fullName.strip();
        if (fullName.length() > 150) fullName = fullName.substring(0, 150);
        long id;
        try {
            id = accounts.insert(email, passwords.encode(UUID.randomUUID().toString()), fullName, null,
                    "CUSTOMER", "ACTIVE", clock.instant());
            jdbc.update("UPDATE users SET google_sub = ?, avatar_url = ? WHERE id = ?", subject,
                    token.getClaimAsString("picture"), id);
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict("Google identity or email is already registered");
        }
        audit(id);
        return sessions.open(accounts.findById(id).orElseThrow());
    }

    private boolean validIssuerAndAudience(Jwt jwt) {
        return jwt.getIssuer() != null && ("accounts.google.com".equals(jwt.getIssuer().toString())
                || "https://accounts.google.com".equals(jwt.getIssuer().toString()))
                && jwt.getAudience().contains(clientId);
    }

    private void audit(long userId) {
        jdbc.update("""
                INSERT INTO user_account_action_logs(target_user_id, performed_by_id, action, new_status)
                VALUES (?, ?, 'GOOGLE_LOGIN', 'ACTIVE')
                """, userId, userId);
    }
}
