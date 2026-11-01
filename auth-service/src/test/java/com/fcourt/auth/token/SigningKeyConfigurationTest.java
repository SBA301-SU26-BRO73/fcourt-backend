package com.fcourt.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SigningKeyConfigurationTest {
    @Test
    void blankPrivateKeyGeneratesAnEphemeralRs256SigningKey() {
        var key = SigningKeyConfiguration.generate(null);

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.size()).isGreaterThanOrEqualTo(2048);
    }

    @Test
    void aPkcs8PemKeyIsLoadedAndItsPublicHalfIsDerived() throws Exception {
        var pair = pair();

        var key = SigningKeyConfiguration.fromPem(pem(pair), "key-1");

        assertThat(key.getKeyID()).isEqualTo("key-1");
        assertThat(key.toRSAPublicKey().getModulus())
                .isEqualTo(((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus());
        assertThat(key.isPrivate()).isTrue();
    }

    @Test
    void pemFromAnEnvironmentVariableWithEscapedNewlinesIsAccepted() throws Exception {
        var escaped = pem(pair()).replace("\n", "\\n");

        assertThat(SigningKeyConfiguration.fromPem(escaped, null).isPrivate()).isTrue();
    }

    @Test
    void keyIdDefaultsToTheThumbprintSoRotatedKeysGetDistinctIds() throws Exception {
        var first = SigningKeyConfiguration.fromPem(pem(pair()), "");
        var second = SigningKeyConfiguration.fromPem(pem(pair()), " ");

        assertThat(first.getKeyID()).isNotBlank().isNotEqualTo(second.getKeyID());
    }

    @Test
    void garbageInsteadOfAPrivateKeyFailsStartupWithAClearMessage() {
        assertThatThrownBy(() -> SigningKeyConfiguration.fromPem("not a key", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS8");
    }

    static java.security.KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static String pem(java.security.KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }
}
