package com.fcourt.subscription.payos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PayosPropertiesTest {
    @Test
    void isConfiguredOnlyWhenEverySecretAndBothRedirectUrlsArePresent() {
        assertThat(properties("id", "key", "checksum", "https://r", "https://c").isConfigured()).isTrue();
        assertThat(properties("", "key", "checksum", "https://r", "https://c").isConfigured()).isFalse();
        assertThat(properties("id", null, "checksum", "https://r", "https://c").isConfigured()).isFalse();
        assertThat(properties("id", "key", " ", "https://r", "https://c").isConfigured()).isFalse();
        assertThat(properties("id", "key", "checksum", "", "https://c").isConfigured()).isFalse();
        assertThat(properties("id", "key", "checksum", "https://r", null).isConfigured()).isFalse();
    }

    static PayosProperties properties(String id, String key, String checksum, String returnUrl, String cancelUrl) {
        return new PayosProperties(id, key, checksum, "https://api-merchant.payos.vn", returnUrl, cancelUrl,
                Duration.ofMinutes(15), null);
    }
}
