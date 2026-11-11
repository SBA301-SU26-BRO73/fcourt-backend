package com.fcourt.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpBranchDirectoryTest {
    HttpServer server;
    String url;
    final AtomicReference<String> tokenSeen = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/branches/3/owner", exchange -> reply(exchange, 200, "{\"branchId\":3,\"ownerId\":70}"));
        server.createContext("/api/internal/branches/4/owner", exchange -> reply(exchange, 404, "{}"));
        server.createContext("/api/internal/branches/5/owner", exchange -> reply(exchange, 500, "{}"));
        server.createContext("/api/internal/branches/3/staff-user-ids", exchange -> {
            tokenSeen.set(exchange.getRequestHeaders().getFirst(HttpBranchDirectory.TOKEN_HEADER));
            reply(exchange, 200, "{\"branchId\":3,\"userIds\":[81,82]}");
        });
        server.createContext("/api/internal/branches/6/staff-user-ids", exchange -> reply(exchange, 503, "{}"));
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void findsTheOwnerOfABranch() {
        assertThat(new HttpBranchDirectory(url, url, "t").ownerOf(3).getAsLong()).isEqualTo(70);
    }

    @Test
    void anUnknownBranchHasNoOwner() {
        assertThat(new HttpBranchDirectory(url, url, "t").ownerOf(4)).isEmpty();
    }

    @Test
    void aFacilityFailureIsRaisedSoTheEventIsRetried() {
        assertThatThrownBy(() -> new HttpBranchDirectory(url, url, "t").ownerOf(5)).isNotNull();
    }

    @Test
    void sendsTheTokenAndReadsTheStaffUserIds() {
        assertThat(new HttpBranchDirectory(url, url, "secret-token").staffOf(3)).containsExactly(81L, 82L);
        assertThat(tokenSeen.get()).isEqualTo("secret-token");
    }

    @Test
    void withoutATokenStaffAreSkippedWithoutACall() {
        assertThat(new HttpBranchDirectory(url, url, "").staffOf(3)).isEmpty();
        assertThat(tokenSeen.get()).isNull();
    }

    @Test
    void aStaffDirectoryFailureOnlySkipsTheStaff() {
        assertThat(new HttpBranchDirectory(url, url, "t").staffOf(6)).isEmpty();
        assertThat(new HttpBranchDirectory("http://127.0.0.1:1", "http://127.0.0.1:1", "t").staffOf(3)).isEmpty();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
