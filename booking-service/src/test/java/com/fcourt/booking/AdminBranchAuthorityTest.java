package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AdminBranchAuthorityTest {
    private HttpServer server;
    private AdminBranchAuthority authority;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>("[{\"id\":7,\"adminId\":100,\"name\":\"Branch\"}]");
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/admin/branches", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        authority = new AdminBranchAuthority(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void failsClosedForForeignDuplicateMissingAndMalformedBranchData() {
        for (String invalid : List.of("null", "{}", "not-json", "[null]", "[{\"id\":7}]",
                "[{\"id\":7,\"adminId\":200}]", "[{\"id\":0,\"adminId\":100}]",
                "[{\"id\":7,\"adminId\":100},{\"id\":7,\"adminId\":100}]")) {
            body.set(invalid);
            assertThatThrownBy(() -> authority.ownedBranches(token("ADMIN"))).isInstanceOfSatisfying(
                    ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
        for (int code : List.of(401, 403, 404, 500)) {
            status.set(code);
            assertThatThrownBy(() -> authority.ownedBranches(token("ADMIN"))).isInstanceOfSatisfying(
                    ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(
                            code == 401 || code == 403 ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE));
        }
        server.stop(0);
        assertThatThrownBy(() -> authority.ownedBranches(token("ADMIN"))).isInstanceOfSatisfying(
                ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Jwt token(String role) {
        return Jwt.withTokenValue("fixture-token").header("alg", "RS256").subject("100")
                .claim("roles", List.of(role)).build();
    }

    @Test
    void rejectsUnauthenticatedAndForeignRolesBeforeContactingFacility() {
        assertThatThrownBy(() -> authority.ownedBranches(null)).isInstanceOfSatisfying(
                ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        for (String role : List.of("CUSTOMER", "STAFF", "SUPER_ADMIN")) {
            assertThatThrownBy(() -> authority.ownedBranches(token(role))).isInstanceOfSatisfying(
                    ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        var invalid = Jwt.withTokenValue("bad-subject").header("alg", "RS256").subject("abc")
                .claim("roles", List.of("ADMIN")).build();
        assertThatThrownBy(() -> authority.ownedBranches(invalid)).isInstanceOfSatisfying(
                ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(calls.get()).isZero();
    }

    @Test
    void forwardsCallerTokenAndRechecksOwnershipOnEveryRead() {
        assertThat(authority.ownedBranches(token("ADMIN"))).containsExactly(7L);
        assertThat(authorization.get()).isEqualTo("Bearer fixture-token");
        body.set("[]");
        assertThat(authority.ownedBranches(token("ADMIN"))).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
        var legacyRole = Jwt.withTokenValue("legacy").header("alg", "RS256").subject("100")
                .claim("role", "ADMIN").build();
        assertThat(authority.ownedBranches(legacyRole)).isEmpty();
    }
}
