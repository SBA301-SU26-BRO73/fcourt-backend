package com.fcourt.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Limiter on, real Redis client pointed at a closed port: every request must still be forwarded. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.enabled=true", "spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@ActiveProfiles("test")
@org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
class RateLimitRedisDownTests {
    static WireMockServer backend;

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (backend == null) {
            backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            backend.start();
            backend.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("from-service")));
        }
        registry.add("fcourt.gateway.jwt.jwk-set-uri", () -> backend.baseUrl() + "/.well-known/jwks.json");
        for (String service : GatewaySecurityTests.SERVICES) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", backend::baseUrl);
        }
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @Test
    void requestsAreAllowedWhenRedisIsDown() {
        for (int i = 0; i < 8; i++) {
            client.post().uri("/api/public/bookings/lookup").exchange().expectStatus().isOk();
            client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        }
        client.get().uri("/api/public/branches").exchange().expectStatus().isOk();
    }

    @Test
    void healthStaysUpWhenRedisIsDown() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
