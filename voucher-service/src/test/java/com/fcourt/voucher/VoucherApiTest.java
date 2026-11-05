package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"voucher.grpc.port=0", "voucher.grpc.token=fixture-service-token"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
class VoucherApiTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("voucher_db");
    static final RSAKey KEY = signingKey();
    static final AtomicReference<String> BRANCHES = new AtomicReference<>();
    static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    static final AtomicInteger UPSTREAM_STATUS = new AtomicInteger(200);
    static final AtomicInteger CALLS = new AtomicInteger();
    static final HttpServer FACILITY = startFacility();
    static final String ISSUER = "http://voucher-test/auth";

    static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("fixture-key").generate(); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    static HttpServer startFacility() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/admin/branches", exchange -> {
                CALLS.incrementAndGet();
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = BRANCHES.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(UPSTREAM_STATUS.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("voucher.facility-url", () -> "http://127.0.0.1:" + FACILITY.getAddress().getPort());
        registry.add("voucher.jwk-set-uri", () -> "http://127.0.0.1:" + FACILITY.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("voucher.jwt-issuer", () -> ISSUER);
    }

    @TestConfiguration static class Time {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2030-01-10T00:00:00Z"), ZoneOffset.UTC); }
    }
    @AfterAll static void stop() { FACILITY.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired VoucherGrpcServer grpc;
    @Autowired VoucherPricing pricing;
    @Autowired VoucherService service;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE vouchers RESTART IDENTITY");
        BRANCHES.set("[{\"id\":7,\"adminId\":100}]");
        CALLS.set(0);
        UPSTREAM_STATUS.set(200);
    }

    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("100").claim("roles", List.of("ADMIN")));
    }
    ObjectNode request(String code) {
        return json.createObjectNode().put("code", code).put("branchId", 7).put("discountType", "PERCENTAGE")
                .put("discountValue", "20.00").put("maxDiscountAmount", "30000.00").put("minOrderAmount", "100000.00")
                .put("startDate", "2030-01-01T00:00:00Z").put("endDate", "2030-02-01T00:00:00Z").put("usageLimit", 10);
    }
    long create(String code) throws Exception {
        String result = mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json")
                .content(request(code).toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("id").asLong();
    }

    @Test
    void createsReadsUpdatesAndSoftDeletesVoucherWithoutAllowingClientCounters() throws Exception {
        var body = request(" save_20 ").put("usedCount", 9);
        String result = mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SAVE_20"))
                .andExpect(jsonPath("$.usedCount").value(0)).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(result).path("id").asLong();
        mvc.perform(get("/api/admin/vouchers/" + id).with(admin())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        body.put("discountType", "FIXED_AMOUNT").put("discountValue", "15000.50").put("usageLimit", 20);
        mvc.perform(put("/api/admin/vouchers/" + id).with(admin()).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discountValue").value(15000.50))
                .andExpect(jsonPath("$.usedCount").value(0));
        mvc.perform(delete("/api/admin/vouchers/" + id).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/vouchers/" + id).with(admin())).andExpect(status().isNotFound());
        assertThat(pricing.validate("SAVE_20", 7, "100000").reason()).isEqualTo("NOT_FOUND");
        mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json").content(body.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCannotAccessForeignVouchersWhileSuperAdminCanManageBothBranches() throws Exception {
        long owned = create("OWNED");
        var superAdmin = jwt().jwt(token -> token.subject("999").claim("roles", List.of("SUPER_ADMIN")));
        BRANCHES.set("[{\"id\":7,\"adminId\":100},{\"id\":8,\"adminId\":200}]");
        var foreign = request("FOREIGN").put("branchId", 8);
        String result = mvc.perform(post("/api/admin/vouchers").with(superAdmin).contentType("application/json").content(foreign.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long foreignId = json.readTree(result).path("id").asLong();
        mvc.perform(get("/api/admin/vouchers").with(superAdmin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/vouchers/" + foreignId).with(superAdmin)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/vouchers/" + foreignId).with(superAdmin).contentType("application/json").content(foreign.toString()))
                .andExpect(status().isOk());
        BRANCHES.set("[{\"id\":7,\"adminId\":100}]");
        mvc.perform(get("/api/admin/vouchers").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/vouchers/" + foreignId).with(admin())).andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/vouchers/" + foreignId).with(admin()).contentType("application/json").content(foreign.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/vouchers/" + foreignId).with(admin())).andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/vouchers/" + owned).with(admin()).contentType("application/json").content(foreign.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json").content(foreign.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/vouchers?branchId=8").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    void invalidFieldsAndCrossFieldRulesAreRejected() throws Exception {
        Object[][] invalid = {{"code", "bad code"}, {"code", "x".repeat(51)}, {"branchId", "0"},
                {"discountType", "OTHER"}, {"discountValue", "0"}, {"discountValue", "100.01"},
                {"discountValue", "1.001"}, {"discountValue", "10000000000"}, {"maxDiscountAmount", "-1"},
                {"minOrderAmount", "-1"}, {"usageLimit", "0"}, {"status", "OTHER"},
                {"startDate", "2031-01-01T00:00:00Z"}, {"startDate", "2030-01-01"},
                {"endDate", "2030-01-01T00:00:00Z"}};
        for (Object[] entry : invalid) {
            var body = request("INVALID").put((String) entry[0], (String) entry[1]);
            mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json").content(body.toString()))
                    .andExpect(status().isBadRequest());
        }
        for (String field : List.of("code", "branchId", "discountType", "discountValue", "startDate", "endDate", "usageLimit")) {
            var body = request("MISSING"); body.remove(field);
            mvc.perform(post("/api/admin/vouchers").with(admin()).contentType("application/json").content(body.toString()))
                    .andExpect(status().isBadRequest());
        }
        for (String query : List.of("page=-1", "page=100001", "size=0", "size=101", "branchId=0")) {
            mvc.perform(get("/api/admin/vouchers?" + query).with(admin())).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/admin/vouchers/0").with(admin())).andExpect(status().isBadRequest());
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void paginationAndUsagePreservationHandleExpiredVouchers() throws Exception {
        long first = create("FIRST");
        long second = create("SECOND");
        mvc.perform(get("/api/admin/vouchers?branchId=7&size=1").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].id").value(second));
        mvc.perform(get("/api/admin/vouchers?size=1&page=1").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first));
        mvc.perform(get("/api/admin/vouchers?size=1&page=99").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items").isEmpty());
        jdbc.update("UPDATE vouchers SET used_count=5 WHERE id=?", first);
        var body = request("FIRST").put("usageLimit", 4);
        mvc.perform(put("/api/admin/vouchers/" + first).with(admin()).contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest());
        body.put("usageLimit", 5).put("usedCount", 0).put("endDate", "2030-01-09T00:00:00Z");
        mvc.perform(put("/api/admin/vouchers/" + first).with(admin()).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usedCount").value(5)).andExpect(jsonPath("$.status").value("EXPIRED"));
        mvc.perform(get("/api/admin/vouchers/" + first).with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        mvc.perform(get("/api/admin/vouchers").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.items[1].status").value("EXPIRED"));
    }

    @Test
    void concurrentDuplicateCodesProduceOneSuccessAndOneConflict() throws Exception {
        var actor = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").header("alg", "RS256")
                .subject("100").claim("roles", List.of("ADMIN")).build();
        var body = json.treeToValue(request(" race "), VoucherRequest.class);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> action = () -> {
            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
            try { service.create(actor, body); return 201; }
            catch (org.springframework.web.server.ResponseStatusException error) { return error.getStatusCode().value(); }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(action);
            var two = executor.submit(action);
            start.countDown();
            assertThat(List.of(one.get(10, java.util.concurrent.TimeUnit.SECONDS), two.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vouchers WHERE code='RACE'", Long.class)).isEqualTo(1);
        long other = create("OTHER");
        mvc.perform(put("/api/admin/vouchers/" + other).with(admin()).contentType("application/json").content(request("race").toString()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT code FROM vouchers WHERE id=?", String.class, other)).isEqualTo("OTHER");
    }

    @Test
    void authenticationRoleAndLiveBranchFailuresNeverExposeVouchers() throws Exception {
        mvc.perform(get("/api/admin/vouchers").header("X-User-Id", "100")).andExpect(status().isUnauthorized());
        for (String role : List.of("CUSTOMER", "STAFF")) {
            mvc.perform(get("/api/admin/vouchers").with(jwt().jwt(token -> token.subject("100").claim("roles", List.of(role)))))
                    .andExpect(status().isForbidden());
        }
        assertThat(CALLS.get()).isZero();
        create("LIVE");
        BRANCHES.set("[]");
        mvc.perform(get("/api/admin/vouchers").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        for (String invalid : List.of("null", "{}", "[null]", "[{\"id\":7}]", "[{\"id\":7,\"adminId\":200}]",
                "[{\"id\":7,\"adminId\":100},{\"id\":7,\"adminId\":100}]")) {
            BRANCHES.set(invalid);
            mvc.perform(get("/api/admin/vouchers").with(admin())).andExpect(status().isServiceUnavailable());
        }
        for (int status : List.of(401, 403, 404, 500)) {
            UPSTREAM_STATUS.set(status);
            mvc.perform(get("/api/admin/vouchers").with(admin())).andExpect(status().is(status < 404 ? 403 : 503));
        }
    }

    @Test
    void realHttpValidatesSignedJwtIssuerAudienceExpiryAndForwardsIdentity() throws Exception {
        create("SIGNED");
        for (String[] values : List.of(new String[] {ISSUER, "fcourt", "60"},
                new String[] {"http://wrong-issuer", "fcourt", "60"}, new String[] {ISSUER, "other", "60"},
                new String[] {ISSUER, "fcourt", "-120"})) {
            var now = Instant.now();
            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(values[0]).audience(values[1]).subject("100")
                    .issueTime(java.util.Date.from(now.minusSeconds(300))).expirationTime(java.util.Date.from(now.plusSeconds(Long.parseLong(values[2]))))
                    .claim("roles", List.of("ADMIN")).build();
            var token = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .keyID(KEY.getKeyID()).build(), claims);
            token.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY));
            var headers = new org.springframework.http.HttpHeaders(); headers.setBearerAuth(token.serialize());
            var result = http.exchange("/api/admin/vouchers", org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), String.class);
            boolean valid = values[0].equals(ISSUER) && values[1].equals("fcourt") && values[2].equals("60");
            assertThat(result.getStatusCode().value()).isEqualTo(valid ? 200 : 401);
            if (valid) {
                assertThat(result.getBody()).contains("SIGNED");
                assertThat(AUTHORIZATION.get()).isEqualTo("Bearer " + token.serialize());
            }
        }
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
    }
}
