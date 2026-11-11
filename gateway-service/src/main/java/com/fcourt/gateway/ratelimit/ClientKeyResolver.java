package com.fcourt.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bucket key: {@code <policy>:user:<sub>} when the request carries a token validated by the gateway
 * (and {@code byUser} is on), otherwise {@code <policy>:ip:<client ip>}. The policy prefix keeps the
 * buckets of different policies apart in Redis.
 */
public class ClientKeyResolver implements KeyResolver {
    private final String policy;
    private final boolean byUser;
    private final boolean trustForwardedFor;

    public ClientKeyResolver(String policy, boolean byUser, boolean trustForwardedFor) {
        this.policy = policy;
        this.byUser = byUser;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String ipKey = policy + ":ip:" + clientIp(exchange, trustForwardedFor);
        if (!byUser) {
            return Mono.just(ipKey);
        }
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(context -> context.getAuthentication())
                .filter(authentication -> authentication instanceof JwtAuthenticationToken
                        && authentication.isAuthenticated())
                .map(authentication -> policy + ":user:"
                        + ((JwtAuthenticationToken) authentication).getToken().getSubject())
                .defaultIfEmpty(ipKey);
    }

    /** First hop of X-Forwarded-For only when trusted (set by the ALB); else the socket peer. */
    static String clientIp(ServerWebExchange exchange, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null) {
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }
}
