package com.fcourt.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** End to end through the real security chain (RS256 + JWKS), Postgres/Flyway and Kafka. */
@SpringBootTest
@AutoConfigureMockMvc
class SuperAdminApiIntegrationTest {
    static final String ISSUER = "http://issuer.test";
    static final String TOPIC = "fcourt.auth.events";
    static final long SUPER_ADMIN = 16;
    static final long CUSTOMER = 6;

    // Singleton containers: they must outlive the cached Spring context (and its outbox
    // scheduler), so they are stopped by Testcontainers' Ryuk at JVM exit, not per class.
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        DATABASE.start();
        KAFKA.start();
    }

    static final RSAKey KEY = rsaKey();
    static final HttpServer JWKS = jwksServer();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("auth.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("auth.jwt.issuer", () -> ISSUER);
        registry.add("auth.jwt.audience", () -> "fcourt");
        registry.add("auth.events.delay-ms", () -> "200");
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Test
    void requestsWithoutValidSuperAdminTokenAreRejected() throws Exception {
        mvc.perform(get("/api/super-admin/court-admins/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/super-admin/court-admins/pending").header("Authorization", "Bearer "
                        + token(SUPER_ADMIN, "SUPER_ADMIN", "another-audience")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/super-admin/court-admins/pending").header("Authorization", bearer(CUSTOMER, "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        mvc.perform(put("/api/super-admin/court-admins/17/approve").header("Authorization", bearer(1, "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminRoleClaimWithoutActiveSuperAdminAccountIsForbidden() throws Exception {
        long locked = jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, role, status, full_name)
                VALUES (?, 'x', 'SUPER_ADMIN', 'LOCKED', 'Locked root') RETURNING id""", Long.class, unique("root"));
        mvc.perform(get("/api/super-admin/court-admins/pending").header("Authorization", bearer(locked, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingQueueListsOwnersWithDocuments() throws Exception {
        long owner = pendingOwner();
        mvc.perform(asSuperAdmin(get("/api/super-admin/court-admins/pending")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItems(17, 18, 19, (int) owner)))
                .andExpect(jsonPath("$[*].status").value(not(hasItem("ACTIVE"))))
                .andExpect(jsonPath("$[?(@.id == 17)].documents[*].docType").value(hasItems("LEGAL", "COURT_IMAGE")));
        mvc.perform(asSuperAdmin(get("/api/super-admin/court-admins/" + owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.documents[0].url").value("https://docs.test/" + owner + "/licence.jpg"))
                .andExpect(jsonPath("$.documents[0].docType").value("LEGAL"));
        mvc.perform(asSuperAdmin(get("/api/super-admin/court-admins/" + CUSTOMER)))
                .andExpect(status().isNotFound());
        mvc.perform(asSuperAdmin(get("/api/super-admin/court-admins/abc")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvePublishesOwnerApprovedEventAndSecondApprovalConflicts() throws Exception {
        long owner = pendingOwner();
        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"documents verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(statusOf(owner)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("SELECT action, previous_status, new_status, reason, performed_by_id "
                + "FROM user_account_action_logs WHERE target_user_id = ?", owner))
                .containsEntry("action", "APPROVE").containsEntry("previous_status", "PENDING_APPROVAL")
                .containsEntry("new_status", "ACTIVE").containsEntry("reason", "documents verified")
                .containsEntry("performed_by_id", SUPER_ADMIN);

        JsonNode event = json.readTree(consume(owner));
        assertThat(event.get("type").asText()).isEqualTo("OwnerApprovedEvent");
        assertThat(event.get("version").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
        assertThat(event.get("data").get("userId").asLong()).isEqualTo(owner);
        assertThat(event.get("data").get("approvedBy").asLong()).isEqualTo(SUPER_ADMIN);
        assertThat(Instant.parse(event.get("data").get("approvedAt").asText())).isBeforeOrEqualTo(Instant.now());

        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/approve")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void usersApproveAliasApprovesWithoutBody() throws Exception {
        long owner = pendingOwner();
        mvc.perform(asSuperAdmin(put("/api/super-admin/users/" + owner + "/approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_outbox WHERE aggregate_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void rejectRequiresReasonAndMovesOwnerToRejected() throws Exception {
        long owner = pendingOwner();
        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/reject")))
                .andExpect(status().isBadRequest());

        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Business licence is expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(statusOf(owner)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT reason FROM user_account_action_logs WHERE target_user_id = ? "
                + "AND action = 'REJECT'", String.class, owner)).isEqualTo("Business licence is expired");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_outbox WHERE aggregate_id = ?", Integer.class, owner))
                .isZero();
        mvc.perform(asSuperAdmin(put("/api/super-admin/court-admins/" + owner + "/approve")))
                .andExpect(status().isConflict());
    }

    @Test
    void toggleStatusLocksAndUnlocksButNeverSuperAdminsOrPendingOwners() throws Exception {
        long customer = jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, role, status, full_name)
                VALUES (?, 'x', 'CUSTOMER', 'ACTIVE', 'Toggle me') RETURNING id""", Long.class, unique("customer"));
        mvc.perform(asSuperAdmin(put("/api/super-admin/users/" + customer + "/toggle-status"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"abuse report\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer))
                .andExpect(jsonPath("$.status").value("LOCKED"));
        mvc.perform(asSuperAdmin(put("/api/super-admin/users/" + customer + "/toggle-status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForList("SELECT action FROM user_account_action_logs WHERE target_user_id = ? ORDER BY id",
                String.class, customer)).containsExactly("LOCK", "UNLOCK");

        mvc.perform(asSuperAdmin(put("/api/super-admin/users/" + SUPER_ADMIN + "/toggle-status")))
                .andExpect(status().isConflict());
        mvc.perform(asSuperAdmin(put("/api/super-admin/users/" + pendingOwner() + "/toggle-status")))
                .andExpect(status().isConflict());
        mvc.perform(asSuperAdmin(put("/api/super-admin/users/999999/toggle-status")))
                .andExpect(status().isNotFound());
    }

    long pendingOwner() {
        long id = jdbc.queryForObject("""
                INSERT INTO users(email, password_hash, role, status, full_name)
                VALUES (?, 'x', 'ADMIN', 'PENDING_APPROVAL', 'Pending owner') RETURNING id""", Long.class, unique("owner"));
        jdbc.update("INSERT INTO user_documents(user_id, url, doc_type) VALUES (?, ?, 'LEGAL')",
                id, "https://docs.test/" + id + "/licence.jpg");
        return id;
    }

    String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    String consume(long key) {
        List<String> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            while (found.isEmpty() && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> {
                    if (String.valueOf(key).equals(record.key())) {
                        found.add(record.value());
                    }
                });
            }
        }
        assertThat(found).as("OwnerApprovedEvent for user %s on %s", key, TOPIC).isNotEmpty();
        return found.getFirst();
    }

    MockHttpServletRequestBuilder asSuperAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", bearer(SUPER_ADMIN, "SUPER_ADMIN"));
    }

    static String bearer(long subject, String role) {
        return "Bearer " + token(subject, role, "fcourt");
    }

    static String token(long subject, String role, String audience) {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(String.valueOf(subject)).audience(audience)
                .claim("roles", List.of(role)).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (JOSEException error) {
            throw new IllegalStateException(error);
        }
        return jwt.serialize();
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.invalid";
    }

    static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException error) {
            throw new IllegalStateException(error);
        }
    }

    static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
