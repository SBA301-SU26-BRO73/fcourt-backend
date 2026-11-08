package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

class ReviewerProfileClientTest {
    HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    ReviewerProfileClient client(int status, String body, AtomicReference<String> auth) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/profile", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new ReviewerProfileClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    static Jwt jwt() { return Jwt.withTokenValue("tok").header("alg", "RS256").subject("10").build(); }

    @Test
    void snapshotUsesCallerTokenMasksNameAndKeepsHttpsAvatar() throws Exception {
        var auth = new AtomicReference<String>();
        var snapshot = client(200, "{\"id\":10,\"email\":\"a@b.c\",\"phone\":\"0909\",\"fullName\":\"Nguyễn Văn An\",\"avatarUrl\":\"https://lh3.googleusercontent.com/a.jpg\"}", auth)
                .snapshot(jwt());
        assertThat(auth.get()).isEqualTo("Bearer tok");
        assertThat(snapshot.displayName()).isEqualTo("An N.");
        assertThat(snapshot.avatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a.jpg");
    }

    @Test
    void unsafeAvatarAndMissingFieldsBecomeNull() throws Exception {
        assertThat(client(200, "{\"fullName\":null,\"avatarUrl\":\"javascript:alert(1)\"}", new AtomicReference<>()).snapshot(jwt()))
                .isEqualTo(ReviewerProfileClient.Snapshot.ANONYMOUS);
        assertThat(ReviewerProfileClient.safeAvatar("http://insecure.test/a.jpg")).isNull();
        assertThat(ReviewerProfileClient.safeAvatar("https://u:p@host.test/a.jpg")).isNull();
        assertThat(ReviewerProfileClient.safeAvatar("  ")).isNull();
    }

    @Test
    void malformedAuthResponsesNeverBreakReviewCreation() throws Exception {
        for (String body : new String[] {"{\"fullName\":5}", "not json at all", "[]", ""}) {
            assertThat(client(200, body, new AtomicReference<>()).snapshot(jwt())).as(body).isEqualTo(ReviewerProfileClient.Snapshot.ANONYMOUS);
            stop();
        }
        for (int status : new int[] {401, 403, 404, 500}) {
            assertThat(client(status, "{}", new AtomicReference<>()).snapshot(jwt())).isEqualTo(ReviewerProfileClient.Snapshot.ANONYMOUS);
            stop();
        }
    }

    @Test
    void unreachableAuthServiceFallsBackToAnonymous() {
        var unreachable = new ReviewerProfileClient(RestClient.builder(), "http://127.0.0.1:1");
        assertThat(unreachable.snapshot(jwt())).isEqualTo(ReviewerProfileClient.Snapshot.ANONYMOUS);
    }

    @Test
    void authFailureDoesNotBlockTheReview() throws Exception {
        assertThat(client(503, "{}", new AtomicReference<>()).snapshot(jwt())).isEqualTo(ReviewerProfileClient.Snapshot.ANONYMOUS);
    }
}
