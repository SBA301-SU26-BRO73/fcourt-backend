package com.fcourt.auth.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** RS256 signing key and {@link JwtEncoder}; the public half is served at {@code /.well-known/jwks.json}. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SigningKeyConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfiguration.class);
    private static final int GENERATED_KEY_BITS = 2048;

    @Bean
    RSAKey signingKey(JwtProperties properties) {
        return properties.privateKey() == null || properties.privateKey().isBlank()
                ? generate(properties.keyId())
                : fromPem(properties.privateKey(), properties.keyId());
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
    }

    static RSAKey generate(String keyId) {
        log.warn("auth.jwt.private-key is not set: using an ephemeral signing key. "
                + "Tokens stop validating on restart and instances do not share keys; set AUTH_JWT_PRIVATE_KEY.");
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_BITS);
            var pair = generator.generateKeyPair();
            return build((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate(), keyId);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot generate signing key", error);
        }
    }

    static RSAKey fromPem(String pem, String keyId) {
        try {
            String body = pem.replace("\\n", "\n")
                    .replaceAll("-----(BEGIN|END) [A-Z ]*PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return build(publicKey, privateKey, keyId);
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException error) {
            throw new IllegalStateException("auth.jwt.private-key must be an RSA private key in PKCS8 PEM format",
                    error);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey, String keyId) {
        var builder = new RSAKey.Builder(publicKey).privateKey(privateKey)
                .algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE);
        try {
            return (keyId == null || keyId.isBlank() ? builder.keyIDFromThumbprint() : builder.keyID(keyId)).build();
        } catch (com.nimbusds.jose.JOSEException error) {
            throw new IllegalStateException("Cannot compute key id", error);
        }
    }
}
