package com.fcourt.auth.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token settings under {@code auth.jwt}. {@code privateKey} is a PKCS8 PEM RSA key;
 * when blank an ephemeral key is generated (development only, see {@link SigningKeyConfiguration}).
 */
@ConfigurationProperties("auth.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String privateKey,
        String keyId,
        @DefaultValue("15m") Duration accessTtl,
        @DefaultValue("30d") Duration refreshTtl) {
}
