package com.fcourt.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate limiting (prefix {@code gateway.rate-limit}). Each policy allows {@code requests} per
 * {@code windowSeconds}, with up to {@code burst} requests at once.
 *
 * @param trustForwardedFor use the first hop of {@code X-Forwarded-For} as the client IP (only behind the ALB)
 * @param redisTimeoutMs    Redis calls slower than this are treated as an outage (the request is allowed)
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue("300") long redisTimeoutMs,
        Policy general,
        Policy guestLookup,
        Policy guestBooking,
        Policy auth) {

    public RateLimitProperties {
        general = general != null ? general : new Policy(50, 1, 100);
        guestLookup = guestLookup != null ? guestLookup : new Policy(5, 60, 5);
        guestBooking = guestBooking != null ? guestBooking : new Policy(20, 60, 20);
        auth = auth != null ? auth : new Policy(10, 60, 10);
    }

    /** {@code burst <= 0} means "same as requests". */
    public record Policy(int requests, int windowSeconds, int burst) {
        public Policy {
            if (requests <= 0 || windowSeconds <= 0) {
                throw new IllegalArgumentException("rate-limit requests and window-seconds must be positive");
            }
            burst = burst <= 0 ? requests : Math.max(burst, requests);
        }

        /** Time to earn back the cost of one request. */
        public int retryAfterSeconds() {
            return (int) Math.max(1, Math.ceil((double) windowSeconds / requests));
        }
    }
}
