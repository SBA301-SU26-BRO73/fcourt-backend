package com.fcourt.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Real gateway context against a WireMock acting as every service (resolved through the simple discovery
 * client, no registry) and as auth-service's JWKS endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
class GatewaySecurityTests {
    static final String ISSUER = "http://auth.test";
    static final List<String> SERVICES = List.of("auth-service", "subscription-service", "facility-service",
            "reporting-service", "discovery-service", "voucher-service", "slot-hold-engine", "booking-service",
            "payment-service", "staff-operations-service", "pos-retail-service", "notification-service");

    static WireMockServer backend;
    static RSAKey signingKey;

    @Autowired
    WebTestClient client;

    @BeforeAll
    static void startBackend() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backend.start();
        backend.stubFor(get(urlEqualTo("/.well-known/jwks.json")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // @DynamicPropertySource runs before @BeforeAll, so the backend is created lazily here.
        if (backend == null) {
            backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            backend.start();
        }
        registry.add("fcourt.gateway.jwt.jwk-set-uri", () -> backend.baseUrl() + "/.well-known/jwks.json");
        registry.add("fcourt.gateway.jwt.issuer", () -> ISSUER);
        for (String service : SERVICES) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", backend::baseUrl);
        }
    }

    @BeforeEach
    void resetStubs() {
        backend.resetRequests();
        backend.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(200).withBody("from-service")));
    }

    static String token(String subject, String audience, String issuer, long ttlSeconds) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject(subject).audience(audience).issuer(issuer)
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + ttlSeconds * 1000))
                .claim("roles", List.of("CUSTOMER")).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    static String validToken() throws JOSEException {
        return token("42", "fcourt", ISSUER, 300);
    }

    @Test
    void publicPathIsForwardedWithoutToken() {
        client.get().uri("/api/public/branches").exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo("from-service");
        backend.verify(getRequestedFor(urlPathEqualTo("/api/public/branches")));
    }

    @ParameterizedTest
    @MethodSource("publicRequests")
    void publicRequestsNeedNoToken(HttpMethod method, String path) {
        client.method(method).uri(path).exchange().expectStatus().isOk();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> publicRequests() {
        return Stream.of(
                of(HttpMethod.POST, "/api/auth/login"), of(HttpMethod.POST, "/api/auth/register/customer"),
                of(HttpMethod.POST, "/api/auth/refresh"), of(HttpMethod.POST, "/api/auth/google"),
                of(HttpMethod.POST, "/api/auth/password/forgot"), of(HttpMethod.POST, "/api/auth/password/reset"),
                of(HttpMethod.GET, "/api/public/branches/7/courts"), of(HttpMethod.GET, "/api/public/branches/7/reviews"),
                of(HttpMethod.GET, "/api/public/courts"), of(HttpMethod.GET, "/api/court-types"),
                of(HttpMethod.GET, "/api/subscription-plans/all"),
                of(HttpMethod.POST, "/api/subscriptions/payment-webhook"),
                of(HttpMethod.POST, "/api/bookings/hold-slots"),
                of(HttpMethod.POST, "/api/bookings"),
                of(HttpMethod.DELETE, "/api/bookings/hold-slots/6f1a"),
                of(HttpMethod.POST, "/api/public/bookings/lookup"), of(HttpMethod.POST, "/api/public/bookings/cancel"), of(HttpMethod.GET, "/api/bookings/42"), of(HttpMethod.GET, "/api/payments/42/status"),
                of(HttpMethod.POST, "/api/public/refunds"), of(HttpMethod.POST, "/api/public/refunds/lookup"),
                of(HttpMethod.GET, "/api/payments/checkout-info/42"),
                of(HttpMethod.POST, "/api/payments/42/upload-receipt"),
                of(HttpMethod.GET, "/actuator/health"));
    }

    private static org.junit.jupiter.params.provider.Arguments of(HttpMethod method, String path) {
        return org.junit.jupiter.params.provider.Arguments.of(method, path);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void protectedRequestsWithoutTokenGet401(HttpMethod method, String path) {
        client.method(method).uri(path).exchange().expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> protectedRequests() {
        return Stream.of(
                of(HttpMethod.GET, "/api/auth/me"), of(HttpMethod.PUT, "/api/auth/profile"),
                of(HttpMethod.GET, "/api/bookings/my"),
                of(HttpMethod.POST, "/api/bookings/42/cancel"), of(HttpMethod.GET, "/api/admin/bookings"),
                of(HttpMethod.GET, "/api/payments/my-transactions"),
                of(HttpMethod.POST, "/api/payments/42/confirm-free"), of(HttpMethod.POST, "/api/refunds/request"),
                of(HttpMethod.PUT, "/devices"), of(HttpMethod.DELETE, "/devices/abc"),
                of(HttpMethod.GET, "/api/notifications/me"), of(HttpMethod.GET, "/api/notifications/me/unread-count"),
                of(HttpMethod.PATCH, "/api/notifications/5/read"), of(HttpMethod.PATCH, "/api/notifications/me/read-all"),
                of(HttpMethod.POST, "/api/vouchers/apply"), of(HttpMethod.POST, "/api/reviews"),
                of(HttpMethod.GET, "/api/staff/schedule"), of(HttpMethod.GET, "/api/admin/dashboard/summary"),
                of(HttpMethod.POST, "/api/court-types"), of(HttpMethod.GET, "/api/subscription-plans"),
                of(HttpMethod.POST, "/api/public/branches"), of(HttpMethod.GET, "/api/unknown"));
    }

    @Test
    void protectedPathWithValidTokenIsForwardedWithAuthorizationUnchanged() throws JOSEException {
        String bearer = "Bearer " + validToken();
        client.get().uri("/api/bookings/my").header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange().expectStatus().isOk();
        backend.verify(getRequestedFor(urlPathEqualTo("/api/bookings/my"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(bearer)));
    }

    @Test
    void invalidTokensAreRejected() throws JOSEException {
        for (String bad : List.of(token("42", "other-audience", ISSUER, 300), token("42", "fcourt", "http://evil", 300),
                token("abc", "fcourt", ISSUER, 300), token("42", "fcourt", ISSUER, -60), "not-a-jwt")) {
            client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad)
                    .exchange().expectStatus().isUnauthorized();
        }
        backend.verify(0, anyRequestedFor(urlPathEqualTo("/api/auth/me")));
    }

    @ParameterizedTest
    @MethodSource("blockedPaths")
    void internalAndActuatorPathsAreBlocked(String path) throws JOSEException {
        client.get().uri(path).exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        client.post().uri(path).exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<String> blockedPaths() {
        return Stream.of("/api/internal/courts/1/slot-occupancy", "/api/internal/bookings/1/pos-charges",
                "/api/internal/staff-accounts", "/actuator/env", "/actuator/prometheus");
    }

    @Test
    void internalServiceTokenFromClientsIsStripped() throws JOSEException {
        client.get().uri("/api/bookings/my").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .header("X-Internal-Service-Token", "secret").exchange().expectStatus().isOk();
        backend.verify(getRequestedFor(urlPathEqualTo("/api/bookings/my"))
                .withoutHeader("X-Internal-Service-Token"));
    }

    @Test
    void swaggerUiAndPerServiceDocsAreReachable() {
        client.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
        client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.urls[0].name").isEqualTo("auth-service");
        client.get().uri("/docs/booking-service/v3/api-docs").exchange().expectStatus().isOk();
        backend.verify(moreThanOrExactly(1), getRequestedFor(urlPathEqualTo("/v3/api-docs")));
    }

    @Test
    void noCorsHandlingByDefault() {
        client.options().uri("/api/public/branches").header(HttpHeaders.ORIGIN, "http://elsewhere")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET").exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
