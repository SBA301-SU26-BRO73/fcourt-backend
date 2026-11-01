package com.fcourt.subscription.payos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PayOS credentials and checkout settings under {@code payos}. The three secrets come from the
 * environment and are never committed. {@code amountOverride} (> 0) replaces the plan price with a
 * fixed amount so the gateway can be tried in its sandbox with a small payment; leave it blank in production.
 */
@ConfigurationProperties("payos")
public record PayosProperties(
        String clientId,
        String apiKey,
        String checksumKey,
        @DefaultValue("https://api-merchant.payos.vn") String apiUrl,
        String returnUrl,
        String cancelUrl,
        @DefaultValue("15m") Duration linkTtl,
        Long amountOverride) {

    /** True when the three secrets and both redirect URLs are present. */
    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(apiKey) && notBlank(checksumKey)
                && notBlank(returnUrl) && notBlank(cancelUrl);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
