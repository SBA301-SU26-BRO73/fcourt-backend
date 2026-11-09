package com.fcourt.gateway.security;

import com.fcourt.gateway.config.GatewayProperties;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Answers 403 for service-to-service and management paths ({@code /api/internal/**}, {@code /actuator/**})
 * before security or routing sees them. The health endpoints of the gateway itself are exempt: they are
 * served locally and never proxied.
 */
@Component
public class BlockedPathsFilter implements WebFilter, Ordered {
    private final List<PathPattern> blocked;
    private final List<PathPattern> local;

    public BlockedPathsFilter(GatewayProperties properties) {
        var parser = PathPatternParser.defaultInstance;
        this.blocked = properties.security().blockedPaths().stream().map(parser::parse).toList();
        this.local = properties.security().localPaths().stream().map(parser::parse).toList();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (local.stream().anyMatch(pattern -> pattern.matches(path))
                || blocked.stream().noneMatch(pattern -> pattern.matches(path))) {
            return chain.filter(exchange);
        }
        return Problems.write(exchange, HttpStatus.FORBIDDEN, "This path is not available through the gateway");
    }
}
