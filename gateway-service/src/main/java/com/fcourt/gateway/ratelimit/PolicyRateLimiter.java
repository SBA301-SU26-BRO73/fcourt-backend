package com.fcourt.gateway.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

/**
 * Redis token bucket for one policy. The bucket works in tokens per second, so "N requests per W seconds"
 * is expressed as: refill N tokens/s, every request costs W tokens, capacity burst * W tokens.
 *
 * <p>Fails open: if Redis errors or does not answer within the timeout the request is allowed and a
 * warning is logged, so a Redis outage never takes the site down. Denied responses carry Retry-After.
 */
public class PolicyRateLimiter extends RedisRateLimiter {
    private static final Log log = LogFactory.getLog(PolicyRateLimiter.class);

    private final String name;
    private final boolean enabled;
    private final RateLimitProperties.Policy policy;
    private final Duration redisTimeout;
    private final RateLimitBackend backend;

    public PolicyRateLimiter(String name, RateLimitProperties.Policy policy, boolean enabled, long redisTimeoutMs,
            RateLimitBackend backend) {
        super(policy.requests(), (long) policy.burst() * policy.windowSeconds(), policy.windowSeconds());
        this.name = name;
        this.policy = policy;
        this.enabled = enabled;
        this.redisTimeout = Duration.ofMillis(redisTimeoutMs);
        this.backend = backend;
        setIncludeHeaders(false);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String key) {
        if (!enabled) {
            return Mono.just(allowed());
        }
        Mono<Response> call = Mono.defer(
                () -> backend != null ? backend.consume(name, key) : super.isAllowed(routeId, key));
        return call.timeout(redisTimeout)
                .map(response -> response.isAllowed() ? response : denied())
                .onErrorResume(error -> {
                    log.warn("Rate limiter '" + name + "' unavailable, request allowed (fail open): " + error);
                    return Mono.just(allowed());
                });
    }

    private Response allowed() {
        return new Response(true, Map.of());
    }

    private Response denied() {
        return new Response(false, Map.of("Retry-After", String.valueOf(policy.retryAfterSeconds())));
    }
}
