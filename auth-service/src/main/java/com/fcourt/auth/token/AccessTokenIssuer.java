package com.fcourt.auth.token;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived RS256 access tokens that every FCOURT service validates against the JWKS:
 * {@code iss}, {@code aud}, numeric {@code sub} (user id) and a {@code roles} array.
 */
@Component
public class AccessTokenIssuer {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final RSAKey signingKey;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties, RSAKey signingKey, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    public IssuedToken issue(long userId, String role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, properties.accessTtl().toSeconds());
    }

    /** {@code expiresInSeconds} is the access token lifetime, as in OAuth 2.0 {@code expires_in}. */
    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
