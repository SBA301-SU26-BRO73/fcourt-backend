package com.fcourt.gateway.ratelimit;

import com.fcourt.gateway.security.Problems;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * RequestRateLimiter ends a denied request with an empty 429. This turns that empty response into the
 * same RFC 7807 JSON the gateway uses elsewhere; the Retry-After header set by {@link PolicyRateLimiter}
 * is kept.
 */
@Component
public class RateLimitProblemFilter implements WebFilter, Ordered {
    static final String DETAIL = "Too many requests, please retry later";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (getStatusCode() != null && getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()
                        && !isCommitted()) {
                    getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                    var body = Problems.json(HttpStatus.TOO_MANY_REQUESTS, DETAIL).getBytes(StandardCharsets.UTF_8);
                    return writeWith(Mono.just(bufferFactory().wrap(body)));
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }
}
