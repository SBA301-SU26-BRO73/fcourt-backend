package com.fcourt.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class RateLimitUnitTests {
    static final RateLimitProperties.Policy LOOKUP = new RateLimitProperties.Policy(5, 60, 5);

    static MockServerWebExchange exchange(String remoteIp, String forwardedFor) throws Exception {
        var builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(remoteIp), 5000));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(builder.build());
    }

    static JwtAuthenticationToken user42() {
        var jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("42")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, java.util.List.of());
    }

    @Test
    void anonymousKeyIsSocketIpWhenForwardedForIsNotTrusted() throws Exception {
        var resolver = new ClientKeyResolver("auth", true, false);
        assertThat(resolver.resolve(exchange("10.0.0.9", "1.2.3.4, 10.0.0.1")).block()).isEqualTo("auth:ip:10.0.0.9");
    }

    @Test
    void anonymousKeyIsFirstForwardedHopWhenTrusted() throws Exception {
        var resolver = new ClientKeyResolver("auth", true, true);
        assertThat(resolver.resolve(exchange("10.0.0.9", "1.2.3.4, 10.0.0.1")).block()).isEqualTo("auth:ip:1.2.3.4");
        assertThat(resolver.resolve(exchange("10.0.0.9", null)).block()).isEqualTo("auth:ip:10.0.0.9");
        assertThat(resolver.resolve(exchange("10.0.0.9", " ")).block()).isEqualTo("auth:ip:10.0.0.9");
    }

    @Test
    void authenticatedKeyIsTokenSubject() throws Exception {
        var resolver = new ClientKeyResolver("general", true, true);
        var key = resolver.resolve(exchange("10.0.0.9", "1.2.3.4"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user42()))
                .block();
        assertThat(key).isEqualTo("general:user:42");
    }

    @Test
    void ipOnlyResolverIgnoresTheToken() throws Exception {
        var resolver = new ClientKeyResolver("auth", false, false);
        var key = resolver.resolve(exchange("10.0.0.9", null))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user42()))
                .block();
        assertThat(key).isEqualTo("auth:ip:10.0.0.9");
    }

    @Test
    void policyMapsToRetryAfterAndBucketSettings() {
        assertThat(LOOKUP.retryAfterSeconds()).isEqualTo(12);
        assertThat(new RateLimitProperties.Policy(50, 1, 100).retryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitProperties.Policy(10, 60, 0).burst()).isEqualTo(10);
    }

    @Test
    void deniedResponseCarriesRetryAfter() {
        RateLimitBackend deny = (policy, key) -> Mono.just(new RateLimiter.Response(false, Map.of()));
        var response = new PolicyRateLimiter("lookup", LOOKUP, true, 300, deny).isAllowed("r", "k").block();
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders()).containsEntry("Retry-After", "12");
    }

    @Test
    void redisErrorFailsOpen() {
        RateLimitBackend broken = (policy, key) -> Mono.error(new IllegalStateException("Redis connection refused"));
        var response = new PolicyRateLimiter("lookup", LOOKUP, true, 300, broken).isAllowed("r", "k").block();
        assertThat(response.isAllowed()).isTrue();
    }

    @Test
    void redisTimeoutFailsOpen() {
        RateLimitBackend hanging = (policy, key) -> Mono.<RateLimiter.Response>never();
        var response = new PolicyRateLimiter("lookup", LOOKUP, true, 50, hanging).isAllowed("r", "k")
                .block(Duration.ofSeconds(5));
        assertThat(response.isAllowed()).isTrue();
    }

    @Test
    void disabledLimiterNeverCallsRedis() {
        RateLimitBackend fail = (policy, key) -> Mono.error(new AssertionError("must not be called"));
        assertThat(new PolicyRateLimiter("lookup", LOOKUP, false, 300, fail).isAllowed("r", "k").block().isAllowed())
                .isTrue();
    }
}
