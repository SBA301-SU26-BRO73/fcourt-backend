package com.fcourt.facility.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.facility.service.ImageStorage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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

class CloudinaryImageStorageTest {
    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void signatureMatchesCloudinaryDocumentationExample() {
        // Example from https://cloudinary.com/documentation/authentication_signatures
        String signature = CloudinaryImageStorage.sign(Map.of(
                "eager", "w_400,h_300,c_pad|w_260,h_200,c_crop", "public_id", "sample_image",
                "timestamp", "1315060510"), "abcd");
        assertThat(signature).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void parsesCloudinaryUrlAndRejectsMalformedOnes() {
        var parsed = CloudinaryImageStorage.Credentials.fromUrl("cloudinary://key123:sec:ret@my-cloud");
        assertThat(parsed.cloudName()).isEqualTo("my-cloud");
        assertThat(parsed.apiKey()).isEqualTo("key123");
        assertThat(parsed.apiSecret()).isEqualTo("sec:ret");
        assertThat(CloudinaryImageStorage.Credentials.fromUrl("").complete()).isFalse();
        assertThat(CloudinaryImageStorage.Credentials.fromUrl("http://x").complete()).isFalse();
        assertThat(CloudinaryImageStorage.Credentials.fromUrl("cloudinary://nokey@cloud").complete()).isFalse();
    }

    CloudinaryImageStorage storageAgainst(String uploadBody, int uploadStatus, AtomicReference<String> captured)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1_1/demo/image/upload", exchange -> respond(exchange, uploadStatus, uploadBody, captured));
        server.createContext("/v1_1/demo/image/destroy", exchange -> respond(exchange, 200, "{\"result\":\"ok\"}", captured));
        server.start();
        var clock = Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC);
        return new CloudinaryImageStorage(new CloudinaryImageStorage.Credentials("demo", "k", "s"),
                "http://127.0.0.1:" + server.getAddress().getPort(), clock);
    }

    static void respond(HttpExchange exchange, int status, String body, AtomicReference<String> captured)
            throws IOException {
        captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void storeSendsSignedMultipartAndReturnsSecureUrl() throws Exception {
        var captured = new AtomicReference<String>();
        var storage = storageAgainst("{\"secure_url\":\"https://res.cloudinary.com/demo/a.png\","
                + "\"public_id\":\"fcourt/branches/1/a\",\"extra\":1}", 200, captured);
        var stored = storage.store("fcourt/branches/1", new ImageStorage.Upload("a.png", "image/png", new byte[] {1, 2, 3}));
        assertThat(stored.url()).isEqualTo("https://res.cloudinary.com/demo/a.png");
        assertThat(stored.publicId()).isEqualTo("fcourt/branches/1/a");
        String expected = CloudinaryImageStorage.sign(
                Map.of("folder", "fcourt/branches/1", "timestamp", "1700000000"), "s");
        assertThat(captured.get()).contains("name=\"api_key\"", "name=\"file\"; filename=\"a.png\"")
                .contains(expected).contains("fcourt/branches/1");
        assertThat(captured.get()).doesNotContain("api_secret");
    }

    @Test
    void remoteFailureBecomesBadGatewayNotSilentSuccess() throws Exception {
        var storage = storageAgainst("{\"error\":{\"message\":\"Invalid Signature\"}}", 401, new AtomicReference<>());
        assertThatThrownBy(() -> storage.store("f", new ImageStorage.Upload("a.png", "image/png", new byte[] {1})))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void deleteSendsSignedPublicId() throws Exception {
        var captured = new AtomicReference<String>();
        var storage = storageAgainst("{}", 200, captured);
        storage.delete("fcourt/branches/1/a");
        assertThat(captured.get()).contains("public_id=fcourt%2Fbranches%2F1%2Fa").contains(CloudinaryImageStorage.sign(
                Map.of("public_id", "fcourt/branches/1/a", "timestamp", "1700000000"), "s"));
    }
}
