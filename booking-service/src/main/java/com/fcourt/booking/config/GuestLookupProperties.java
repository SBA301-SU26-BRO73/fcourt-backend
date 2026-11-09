package com.fcourt.booking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** booking.guest-lookup.*  Giới hạn tạm thời trong service cho tới khi gateway có rate limit. */
@ConfigurationProperties("booking.guest-lookup")
public record GuestLookupProperties(
        Integer ipLimit, Integer codeLimit, Duration window, Boolean trustForwardedFor, Integer maxTrackedKeys) {
    public GuestLookupProperties {
        ipLimit = ipLimit == null ? 10 : ipLimit;
        codeLimit = codeLimit == null ? 5 : codeLimit;
        window = window == null ? Duration.ofMinutes(15) : window;
        trustForwardedFor = trustForwardedFor != null && trustForwardedFor;
        maxTrackedKeys = maxTrackedKeys == null ? 50_000 : maxTrackedKeys;
        if (ipLimit < 1 || codeLimit < 1 || window.isZero() || window.isNegative() || maxTrackedKeys < 1)
            throw new IllegalArgumentException("Invalid booking.guest-lookup settings");
    }
}
