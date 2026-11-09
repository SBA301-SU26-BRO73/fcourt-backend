package com.fcourt.gateway.security;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Minimal RFC 7807 bodies for errors the gateway itself produces. */
final class Problems {
    private Problems() {}

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        var body = "{\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
