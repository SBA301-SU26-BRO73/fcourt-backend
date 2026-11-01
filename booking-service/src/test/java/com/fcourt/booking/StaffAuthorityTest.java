package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class StaffAuthorityTest {
    private Jwt token(String role) {
        return Jwt.withTokenValue("fixture")
                .header("alg", "RS256")
                .subject("100")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("roles", List.of(role))
                .build();
    }

    @Test
    void liveAssignmentIsRequiredAndForeignRolesFailBeforeHttp() throws Exception {
        var status = new AtomicInteger(200);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/api/staff/me",
                exchange -> {
                    calls.incrementAndGet();
                    byte[] body =
                            "{\"userId\":100,\"branchId\":7,\"active\":true,\"email\":\"fixture@example.test\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            var authority =
                    new StaffAuthority(
                            RestClient.builder(),
                            "http://localhost:" + server.getAddress().getPort());
            authority.require(token("STAFF"), 7, 100);
            assertThatThrownBy(() -> authority.require(token("ADMIN"), 7, 100))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(() -> authority.require(token("STAFF"), 8, 100))
                    .isInstanceOf(ResponseStatusException.class);
            status.set(403);
            assertThatThrownBy(() -> authority.require(token("STAFF"), 7, 100))
                    .isInstanceOf(ResponseStatusException.class);
            status.set(500);
            assertThatThrownBy(() -> authority.require(token("STAFF"), 7, 100))
                    .isInstanceOf(ResponseStatusException.class);
        } finally {
            server.stop(0);
        }
    }
}
