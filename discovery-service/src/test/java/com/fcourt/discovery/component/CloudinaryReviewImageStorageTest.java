package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CloudinaryReviewImageStorageTest {
    HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    CloudinaryReviewImageStorage storage(String body, int status, AtomicReference<String> captured) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1_1/demo/image/upload", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new CloudinaryReviewImageStorage(new CloudinaryReviewImageStorage.Credentials("demo", "k", "s"),
                "http://127.0.0.1:" + server.getAddress().getPort(), Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
    }

    @Test
    void signatureMatchesCloudinaryDocumentationExample() {
        assertThat(CloudinaryReviewImageStorage.sign(Map.of("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop",
                "public_id", "sample_image", "timestamp", "1315060510"), "abcd")).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void parsesCloudinaryUrl() {
        assertThat(CloudinaryReviewImageStorage.Credentials.fromUrl("cloudinary://key:sec:ret@my-cloud").cloudName()).isEqualTo("my-cloud");
        assertThat(CloudinaryReviewImageStorage.Credentials.fromUrl("http://x").complete()).isFalse();
        assertThat(CloudinaryReviewImageStorage.Credentials.fromUrl("").complete()).isFalse();
    }

    @Test
    void storeSendsSignedMultipartWithFixedFileNameAndReturnsSecureUrl() throws Exception {
        var captured = new AtomicReference<String>();
        var url = storage("{\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v1/fcourt/reviews/10/a.png\",\"public_id\":\"x\"}", 200, captured)
                .store("fcourt/reviews/10", new ReviewImageStorage.Upload("image/png", new byte[] {1, 2, 3}));
        assertThat(url).isEqualTo("https://res.cloudinary.com/demo/image/upload/v1/fcourt/reviews/10/a.png");
        String signature = CloudinaryReviewImageStorage.sign(Map.of("folder", "fcourt/reviews/10", "timestamp", "1700000000"), "s");
        assertThat(captured.get()).contains("name=\"api_key\"", "name=\"file\"; filename=\"image\"", signature, "fcourt/reviews/10")
                .doesNotContain("api_secret");
    }

    @Test
    void remoteFailureBecomesBadGateway() throws Exception {
        var storage = storage("{\"error\":{\"message\":\"Invalid Signature\"}}", 401, new AtomicReference<>());
        assertThatThrownBy(() -> storage.store("f", new ReviewImageStorage.Upload("image/png", new byte[] {1})))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void listUsesBasicAuthPrefixAndCursorAndParsesAssets() throws Exception {
        var request = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1_1/demo/resources/image/upload", exchange -> {
            request.set(exchange.getRequestURI().getRawQuery() + "|" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = ("{\"resources\":[{\"public_id\":\"fcourt/reviews/1/a\",\"secure_url\":\"https://res.cloudinary.com/demo/image/upload/v1/fcourt/reviews/1/a.jpg\","
                    + "\"created_at\":\"2026-11-07T01:02:03Z\",\"bytes\":1},{\"public_id\":\"broken\"}],\"next_cursor\":\"abc\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var storage = new CloudinaryReviewImageStorage(new CloudinaryReviewImageStorage.Credentials("demo", "k", "s"),
                "http://127.0.0.1:" + server.getAddress().getPort(), Clock.systemUTC());
        var page = storage.list("fcourt/reviews/", "cur");
        assertThat(page.assets()).hasSize(1);
        assertThat(page.assets().get(0).publicId()).isEqualTo("fcourt/reviews/1/a");
        assertThat(page.assets().get(0).createdAt()).isEqualTo(Instant.parse("2026-11-07T01:02:03Z"));
        assertThat(page.nextCursor()).isEqualTo("abc");
        assertThat(request.get()).contains("prefix=fcourt/reviews/", "next_cursor=cur", "max_results=100")
                .endsWith("Basic " + java.util.Base64.getEncoder().encodeToString("k:s".getBytes(StandardCharsets.UTF_8)));
        assertThat(storage.supportsCleanup()).isTrue();
    }

    @Test
    void deleteSendsSignedPublicIdAndTreatsNotFoundAsDone() throws Exception {
        var captured = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1_1/demo/image/destroy", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"result\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var storage = new CloudinaryReviewImageStorage(new CloudinaryReviewImageStorage.Credentials("demo", "k", "s"),
                "http://127.0.0.1:" + server.getAddress().getPort(), Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        storage.delete("fcourt/reviews/1/a");
        assertThat(captured.get()).contains("public_id=fcourt%2Freviews%2F1%2Fa").contains(CloudinaryReviewImageStorage.sign(
                Map.of("public_id", "fcourt/reviews/1/a", "timestamp", "1700000000"), "s")).doesNotContain("api_secret");
    }

    @Test
    void listAndDeleteFailuresBecomeBadGateway() throws Exception {
        var storage = storage("{}", 500, new AtomicReference<>());
        assertThatThrownBy(() -> storage.list("p", null)).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        assertThatThrownBy(() -> storage.delete("x")).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void responseWithoutUrlIsNotSilentSuccess() throws Exception {
        var storage = storage("{}", 200, new AtomicReference<>());
        assertThatThrownBy(() -> storage.store("f", new ReviewImageStorage.Upload("image/png", new byte[] {1})))
                .isInstanceOf(ResponseStatusException.class);
    }
}
