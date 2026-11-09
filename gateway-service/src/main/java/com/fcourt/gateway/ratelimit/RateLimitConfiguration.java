package com.fcourt.gateway.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Limiter + key resolver per policy, referenced from the routes in application.yml as
 * {@code #{@guestLookupRateLimiter}} and so on. The general policy is {@code @Primary}: it is the one
 * Spring Cloud Gateway injects by type.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    @Primary
    PolicyRateLimiter generalRateLimiter(RateLimitProperties p, ObjectProvider<RateLimitBackend> backend) {
        return new PolicyRateLimiter("general", p.general(), p.enabled(), p.redisTimeoutMs(), backend.getIfAvailable());
    }

    @Bean
    PolicyRateLimiter guestLookupRateLimiter(RateLimitProperties p, ObjectProvider<RateLimitBackend> backend) {
        return new PolicyRateLimiter("guest-lookup", p.guestLookup(), p.enabled(), p.redisTimeoutMs(),
                backend.getIfAvailable());
    }

    @Bean
    PolicyRateLimiter authRateLimiter(RateLimitProperties p, ObjectProvider<RateLimitBackend> backend) {
        return new PolicyRateLimiter("auth", p.auth(), p.enabled(), p.redisTimeoutMs(), backend.getIfAvailable());
    }

    /** Token subject when authenticated, else client IP. */
    @Bean
    @Primary
    KeyResolver generalKeyResolver(RateLimitProperties p) {
        return new ClientKeyResolver("general", true, p.trustForwardedFor());
    }

    @Bean
    KeyResolver guestLookupKeyResolver(RateLimitProperties p) {
        return new ClientKeyResolver("guest-lookup", false, p.trustForwardedFor());
    }

    @Bean
    KeyResolver authKeyResolver(RateLimitProperties p) {
        return new ClientKeyResolver("auth", false, p.trustForwardedFor());
    }
}
