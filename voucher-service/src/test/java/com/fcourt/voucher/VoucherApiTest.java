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
}
