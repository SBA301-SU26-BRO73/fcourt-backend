package com.fcourt.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class AccessTokenIssuerTest {
    static final String ISSUER = "http://issuer.test";
    static final String AUDIENCE = "fcourt";

    RSAKey key;
    AccessTokenIssuer issuer;
    Instant now;

    @BeforeEach
    void setUp() {
        key = SigningKeyConfiguration.generate("kid-1");
        now = Instant.now();
        var properties = new JwtProperties(ISSUER, AUDIENCE, null, null, Duration.ofMinutes(15), Duration.ofDays(30));
        issuer = new AccessTokenIssuer(new SigningKeyConfiguration().jwtEncoder(key), properties, key,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void tokenCarriesTheClaimsEveryServiceRelyOn() throws Exception {
        var token = issuer.issue(42, "ADMIN");

        var jwt = decoder(key).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getIssuer()).hasToString(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getHeaders()).containsEntry("kid", "kid-1").containsEntry("alg", "RS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void tokenSignedWithAnotherKeyDoesNotValidate() throws Exception {
        var token = issuer.issue(42, "CUSTOMER");

        assertThatThrownBy(() -> decoder(SigningKeyConfiguration.generate("other")).decode(token.value()))
                .isInstanceOf(BadJwtException.class);
    }

    static JwtDecoder decoder(RSAKey key) throws Exception {
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    }
}
