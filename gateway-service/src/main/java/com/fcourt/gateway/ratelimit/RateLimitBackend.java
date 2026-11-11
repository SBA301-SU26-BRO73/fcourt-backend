package com.fcourt.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import reactor.core.publisher.Mono;

/**
 * Optional replacement for the Redis token bucket. No bean of this type exists in production (Redis is
 * used); tests provide an in-memory one so no Redis is needed.
 */
@FunctionalInterface
public interface RateLimitBackend {
    Mono<RateLimiter.Response> consume(String policy, String key);
}
