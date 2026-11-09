package com.fcourt.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fcourt.gateway.ratelimit.RateLimitBackend;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Whole gateway with the rate limiter switched on and an in-memory token counter in place of Redis
 * (limits: guest-lookup 5, auth 10, general 1000). Each test uses its own client IP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.rate-limit.enabled=true", "gateway.rate-limit.trust-forwarded-for=true"})
@ActiveProfiles("test")
@org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
@Import(RateLimitGatewayTests.InMemoryBackend.class)
class RateLimitGatewayTests {
    static final String ISSUER = "http://auth.test";
    static final Map<String, Integer> LIMITS = Map.of("guest-lookup", 5, "auth", 10, "general", 1000);
    static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    static final List<String> KEYS = new CopyOnWriteArrayList<>();

    static WireMockServer backend;
    static RSAKey signingKey;

    @Autowired
    WebTestClient client;

    @TestConfiguration
    static class InMemoryBackend {
        @Bean
        RateLimitBackend inMemoryBackend() {
            return (policy, key) -> {
                KEYS.add(key);
                boolean allowed = COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet()
                        <= LIMITS.get(policy);
                return Mono.just(new RateLimiter.Response(allowed, Map.of()));
            };
        }
    }

    @BeforeAll
    static void startBackend() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        startWireMock();
    }

    private static void startWireMock() {
        if (backend == null) {
            backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            backend.start();
        }
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        startWireMock();
        registry.add("fcourt.gateway.jwt.jwk-set-uri", () -> backend.baseUrl() + "/.well-known/jwks.json");
        registry.add("fcourt.gateway.jwt.issuer", () -> ISSUER);
        for (String service : GatewaySecurityTests.SERVICES) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", backend::baseUrl);
        }
    }

    @BeforeEach
    void resetStubs() {
        COUNTS.clear();
        KEYS.clear();
        backend.stubFor(get(urlEqualTo("/.well-known/jwks.json")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
        backend.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(200).withBody("from-service")));
    }

    static String token(String subject) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject(subject).audience("fcourt").issuer(ISSUER)
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    WebTestClient.ResponseSpec post(String path, String ip) {
        return client.post().uri(path).header("X-Forwarded-For", ip).exchange();
    }

    @Test
    void guestLookupIsLimitedPerIpWithProblemJsonAndRetryAfter() {
        for (int i = 0; i < 5; i++) {
            post("/api/public/bookings/lookup", "203.0.113.1").expectStatus().isOk();
        }
        post("/api/public/bookings/lookup", "203.0.113.1").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "12")
                .expectBody().jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Too Many Requests").jsonPath("$.detail").exists();
        // Another client is not affected.
        post("/api/public/bookings/lookup", "203.0.113.2").expectStatus().isOk();
    }

    @Test
    void otherRoutesAreUnaffectedByTheLookupLimit() {
        for (int i = 0; i < 6; i++) {
            post("/api/public/bookings/lookup", "203.0.113.3");
        }
        client.get().uri("/api/public/branches").header("X-Forwarded-For", "203.0.113.3").exchange()
                .expectStatus().isOk();
        // Only POST lookup is strict: other methods on the same prefix use the general limit.
        client.get().uri("/api/public/bookings/lookup").header("X-Forwarded-For", "203.0.113.3").exchange()
                .expectStatus().isOk();
    }

    @Test
    void loginForgotAndRegisterShareTheAuthLimit() {
        String ip = "203.0.113.4";
        for (int i = 0; i < 4; i++) {
            post("/api/auth/login", ip).expectStatus().isOk();
        }
        for (int i = 0; i < 3; i++) {
            post("/api/auth/password/forgot", ip).expectStatus().isOk();
        }
        for (int i = 0; i < 3; i++) {
            post("/api/auth/register/customer", ip).expectStatus().isOk();
        }
        post("/api/auth/login", ip).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        post("/api/auth/register/customer", ip).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Refresh and reset are not part of the strict policy.
        post("/api/auth/refresh", ip).expectStatus().isOk();
        post("/api/auth/password/reset", ip).expectStatus().isOk();
    }

    @Test
    void anonymousIsKeyedByForwardedIpAndAuthenticatedByTokenSubject() throws JOSEException {
        client.get().uri("/api/public/branches").header("X-Forwarded-For", "198.51.100.7, 10.0.0.1").exchange()
                .expectStatus().isOk();
        assertThat(KEYS).contains("general:ip:198.51.100.7");
        KEYS.clear();
        client.get().uri("/api/bookings/my").header("X-Forwarded-For", "198.51.100.7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("42")).exchange().expectStatus().isOk();
        assertThat(KEYS).contains("general:user:42").doesNotContain("general:ip:198.51.100.7");
    }
}
