package com.fcourt.staff;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "staff.events.enabled=false")
class StaffIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final RSAKey SIGNING_KEY = key("auth");
    static final RSAKey FOREIGN_KEY = key("foreign");
    static final AtomicInteger CREATES = new AtomicInteger();
    static final Map<String, Map<String, Object>> ACCOUNTS = new ConcurrentHashMap<>();
    static final Map<String, Map<String, Object>> CHECK_IN_RESULTS = new ConcurrentHashMap<>();
    static final AtomicBoolean CHECKED_IN = new AtomicBoolean();
    static volatile int bookingFailure;
    static volatile boolean wrongBranch;
    static volatile int bookingDateShift;
    static volatile int failure;
    static volatile boolean active = true;
    static volatile boolean branchActive = true;
    static volatile boolean malformed;
    static volatile boolean wrongAccount;
    static final HttpServer UPSTREAM = server();
    static final String URL = "http://localhost:" + UPSTREAM.getAddress().getPort();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("staff_db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("staff.auth.url", () -> URL);
        registry.add("staff.facility.url", () -> URL);
        registry.add("staff.auth.jwk-set-uri", () -> URL + "/jwks");
        registry.add("staff.auth.issuer", () -> "fcourt-test");
        registry.add("staff.booking.url", () -> URL);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE staff RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE staff_booking_commands, staff_outbox");
        CHECK_IN_RESULTS.clear();
        CHECKED_IN.set(false);
        bookingFailure = bookingDateShift = 0;
        wrongBranch = false;
        ACCOUNTS.clear();
        CREATES.set(0);
        failure = 0;
        active = branchActive = true;
        malformed = wrongAccount = false;
    }

    @AfterAll
    static void stop() { UPSTREAM.stop(0); }

    @Test
    void completeCrudAndStaffAssignmentAreScopedAndAudited() {
        var created = create(UUID.randomUUID(), body(7));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().path("id").asLong();
        assertThat(created.getBody().path("userId").asLong()).isEqualTo(100);
        assertThat(created.getBody().has("password")).isFalse();
        var list = call(HttpMethod.GET, "/api/admin/staff?branchId=7", null, token("1", "ADMIN"), null);
        assertThat(list.getBody().size()).isEqualTo(1);
        assertThat(list.getHeaders().getCacheControl()).contains("no-store");
        var me = call(HttpMethod.GET, "/api/staff/me", null, token("100", "STAFF"), null);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().path("branchId").asLong()).isEqualTo(7);
        var update = call(HttpMethod.PUT, "/api/admin/staff/" + id,
                Map.of("fullName", "Updated", "phone", "0912345679", "shifts", List.of()),
                token("1", "ADMIN"), UUID.randomUUID());
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody().path("fullName").asText()).isEqualTo("Updated");
        assertThat(update.getBody().path("shifts").size()).isZero();
        assertThat(call(HttpMethod.DELETE, "/api/admin/staff/" + id, null,
                token("1", "ADMIN"), UUID.randomUUID()).getBody().path("active").asBoolean()).isFalse();
        assertThat(call(HttpMethod.GET, "/api/staff/me", null,
                token("100", "STAFF"), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_audit", Integer.class)).isEqualTo(3);
    }

    @Test
    void repeatedCreateIsIdempotentAndDoesNotStoreCredentials() {
        UUID command = UUID.randomUUID();
        var first = create(command, body(7));
        var replay = create(command, body(7));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(CREATES.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_commands", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"))
                .noneMatch(row -> row.get("column_name").toString().contains("password"));
    }

    @Test
    void sameKeyWithDifferentMetadataConflicts() {
        UUID key = UUID.randomUUID();
        assertThat(create(key, body(7)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var changed = new java.util.HashMap<>(body(7));
        changed.put("fullName", "Different");
        assertThat(create(key, changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(CREATES.get()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateCreateHasOneAccountAndOneAssignment() throws Exception {
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(ignored -> executor.submit(() -> create(key, body(7)))).toList();
            for (var job : jobs) assertThat(job.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(CREATES.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class)).isEqualTo(1);
    }

    @Test
    void remoteAccountCanBeRecoveredAfterLocalTransactionFails() {
        jdbc.execute("ALTER TABLE staff_audit ADD CONSTRAINT fail_audit CHECK (action <> 'CREATE')");
        UUID key = UUID.randomUUID();
        try {
            assertThat(create(key, body(7)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class)).isZero();
            assertThat(ACCOUNTS.size()).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE staff_audit DROP CONSTRAINT fail_audit");
        }
        assertThat(create(key, body(7)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ACCOUNTS.size()).isEqualTo(1);
    }

    @Test
    void foreignOwnerCannotCreateListUpdateOrDisableStaff() {
        assertThat(create(UUID.randomUUID(), body(8)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/admin/staff?branchId=8", null,
                token("1", "ADMIN"), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long id = create(UUID.randomUUID(), body(7)).getBody().path("id").asLong();
        assertThat(call(HttpMethod.DELETE, "/api/admin/staff/" + id, null,
                token("2", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/admin/staff/" + id,
                Map.of("fullName", "Changed", "phone", "0912345678", "shifts", List.of()),
                token("2", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "STAFF", "SUPER_ADMIN", "ROLE_ADMIN"})
    void unauthorizedRoleCannotUseAdminApi(String role) {
        assertThat(call(HttpMethod.GET, "/api/admin/staff?branchId=7", null,
                token("1", role), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "issuer", "audience", "expired", "subject", "missing-expiry"})
    void signedTokenMustSatisfyEveryVerificationRule(String fault) {
        String token = sign(fault.equals("subject") ? "not-an-id" : "1", "ADMIN",
                fault.equals("issuer") ? "foreign" : "fcourt-test",
                fault.equals("audience") ? "foreign" : "fcourt",
                fault.equals("missing-expiry") ? null : fault.equals("expired")
                        ? Instant.now().minusSeconds(3600) : Instant.now().plusSeconds(300),
                fault.equals("signature") ? FOREIGN_KEY : SIGNING_KEY);
        assertThat(call(HttpMethod.GET, "/api/admin/staff?branchId=7", null, token, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disabledAccountOrBranchDeniesAccessEvenWithValidJwt() {
        active = false;
        assertThat(create(UUID.randomUUID(), body(7)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        active = true;
        branchActive = false;
        assertThat(create(UUID.randomUUID(), body(7)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void upstreamFailuresFailClosed(int status) {
        failure = status;
        assertThat(create(UUID.randomUUID(), body(7)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff", Integer.class)).isZero();
    }

    @Test
    void malformedUpstreamAndIdentityMismatchFailClosed() {
        malformed = true;
        assertThat(create(UUID.randomUUID(), body(7)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        malformed = false;
        wrongAccount = true;
        assertThat(create(UUID.randomUUID(), body(7)).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void invalidBodyHasNoPasswordEchoAndMakesNoAccount() {
        var invalid = new java.util.HashMap<>(body(7));
        invalid.put("password", "secret");
        var response = create(UUID.randomUUID(), invalid);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).doesNotContain("secret");
        assertThat(CREATES.get()).isZero();
    }

    @Test
    void overlappingShiftsAreRejectedBeforeProvisioning() {
        var invalid = new java.util.HashMap<>(body(7));
        invalid.put("shifts", List.of(shift("09:00", "12:00"), shift("11:00", "13:00")));
        assertThat(create(UUID.randomUUID(), invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CREATES.get()).isZero();
    }

    @Test
    void branchReassignmentAndEncodedPasswordOverflowAreRejected() {
        long id = create(UUID.randomUUID(), body(7)).getBody().path("id").asLong();
        assertThat(call(HttpMethod.PUT, "/api/admin/staff/" + id,
                Map.of("fullName", "Staff", "phone", "0912345678", "shifts", List.of(), "branchId", 8),
                token("1", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var invalid = new java.util.HashMap<>(body(7));
        invalid.put("password", "\u00e9".repeat(50));
        assertThat(create(UUID.randomUUID(), invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void paginationAndCommandHeadersAreValidated() {
        assertThat(call(HttpMethod.GET, "/api/admin/staff?branchId=7&limit=101", null,
                token("1", "ADMIN"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/admin/staff", body(7),
                token("1", "ADMIN"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/admin/staff?branchId=7&offset=-1", null,
                token("1", "ADMIN"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void todayScheduleUsesAssignedBranchAndReturnsNoStore() {
        create(UUID.randomUUID(), body(7));
        var response = call(HttpMethod.GET, "/api/staff/schedule/today", null, token("100", "STAFF"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("branchId").asLong()).isEqualTo(7);
        assertThat(response.getBody().path("courts").get(0).path("slots").get(0).path("date").asText())
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString());
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void checkInPersistsOneDurableEventAndSameKeyReplays() {
        create(UUID.randomUUID(), body(7));
        UUID key = UUID.randomUUID();
        var first = checkIn(key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(checkIn(key).getBody()).isEqualTo(first.getBody());
        assertThat(checkIn(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox WHERE published_at IS NULL", Integer.class)).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload FROM staff_outbox", String.class);
        assertThat(payload).contains("BookingCheckedInEvent", "actorId", "QR").doesNotContain("FC-89234", "Bearer");
    }

    @Test
    void concurrentDifferentCheckInCommandsCannotCheckInTwice() throws Exception {
        create(UUID.randomUUID(), body(7));
        int successful = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(ignored -> executor.submit(() -> checkIn(UUID.randomUUID()))).toList();
            for (var request : requests) {
                var status = request.get().getStatusCode();
                assertThat(status).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
                if (status == HttpStatus.OK) successful++;
            }
        }
        assertThat(successful).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void checkInRecoversAfterOutboxInsertRollsBack() {
        create(UUID.randomUUID(), body(7));
        UUID key = UUID.randomUUID();
        jdbc.execute("ALTER TABLE staff_outbox ADD CONSTRAINT reject_event CHECK (booking_id <> 42)");
        try {
            assertThat(checkIn(key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_booking_commands", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE staff_outbox DROP CONSTRAINT reject_event");
        }
        assertThat(checkIn(key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(CHECK_IN_RESULTS).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void malformedScheduleAndCheckInResponsesFailClosed() {
        create(UUID.randomUUID(), body(7));
        wrongBranch = true;
        assertThat(call(HttpMethod.GET, "/api/staff/schedule/today", null, token("100", "STAFF"), null).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(checkIn(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 500})
    void rejectedBookingCheckInDoesNotEmitSuccess(int status) {
        create(UUID.randomUUID(), body(7));
        bookingFailure = status;
        assertThat(checkIn(UUID.randomUUID()).getStatusCode().value()).isEqualTo(status == 500 ? 503 : status);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isZero();
    }

    @Test
    void invalidOrForeignDateCodeAndDifferentKeyMetadataAreRejected() {
        create(UUID.randomUUID(), body(7));
        assertThat(call(HttpMethod.POST, "/api/staff/check-in/verify", Map.of("checkInCode", " "),
                token("100", "STAFF"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        bookingDateShift = 1;
        assertThat(checkIn(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        bookingDateShift = 0;
        CHECKED_IN.set(false);
        UUID key = UUID.randomUUID();
        assertThat(checkIn(key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/staff/check-in/verify", Map.of("checkInCode", "DIFFERENT"),
                token("100", "STAFF"), key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    ResponseEntity<JsonNode> checkIn(UUID key) {
        return call(HttpMethod.POST, "/api/staff/check-in/verify", Map.of("checkInCode", "FC-89234"), token("100", "STAFF"), key);
    }

    ResponseEntity<JsonNode> create(UUID key, Object body) {
        return call(HttpMethod.POST, "/api/admin/staff", body, token("1", "ADMIN"), key);
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String uri, Object body, String token, UUID key) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (key != null) headers.set("Idempotency-Key", key.toString());
        return http.exchange(uri, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    static Map<String, Object> body(long branch) {
        return Map.of("branchId", branch, "email", "staff@example.com", "password", "safe-password-123",
                "fullName", "Staff member", "phone", "0912345678", "shifts", List.of(shift("09:00", "12:00")));
    }

    static Map<String, Object> shift(String start, String end) {
        return Map.of("dayOfWeek", 1, "startTime", start, "endTime", end);
    }

    static String token(String subject, String role) {
        return sign(subject, role, "fcourt-test", "fcourt", Instant.now().plusSeconds(300), SIGNING_KEY);
    }

    static String sign(String subject, String role, String issuer, String audience, Instant expiry, RSAKey key) {
        try {
            var claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                    .expirationTime(expiry == null ? null : Date.from(expiry)).issueTime(Date.from(Instant.now().minusSeconds(60)))
                    .claim("roles", List.of(role)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException error) { throw new IllegalStateException(error); }
    }

    static RSAKey key(String id) {
        try { return new RSAKeyGenerator(2048).keyID(id).generate(); }
        catch (JOSEException error) { throw new IllegalStateException(error); }
    }

    static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", StaffIntegrationTest::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/jwks")) {
            send(exchange, 200, new JWKSet(SIGNING_KEY.toPublicJWK()).toJSONObject());
            return;
        }
        if (failure != 0) { send(exchange, failure, Map.of()); return; }
        if (malformed) { send(exchange, 200, Map.of()); return; }
        try {
            var jwt = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet();
            long actor = Long.parseLong(jwt.getSubject());
            if (path.startsWith("/api/internal/branches/")) {
                if (bookingFailure != 0) { send(exchange, bookingFailure, Map.of()); return; }
                send(exchange, 200, Map.of("branchId", wrongBranch ? 8 : 7, "courts", List.of(Map.of(
                        "courtId", 21, "courtName", "Court 1", "slots", List.of(Map.of("bookingId", 42,
                        "date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(bookingDateShift).toString(),
                        "slotStart", "09:00", "slotEnd", "09:30", "status", "CONFIRMED", "customerName", "Guest"))))));
            } else if (path.equals("/api/internal/bookings/check-in")) {
                if (bookingFailure != 0) { send(exchange, bookingFailure, Map.of()); return; }
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                synchronized (CHECK_IN_RESULTS) {
                    if (CHECK_IN_RESULTS.containsKey(key)) { send(exchange, 200, CHECK_IN_RESULTS.get(key)); return; }
                    if (!CHECKED_IN.compareAndSet(false, true)) { send(exchange, 409, Map.of()); return; }
                    var response = Map.<String, Object>of("bookingId", 42, "branchId", wrongBranch ? 8 : 7,
                            "date", LocalDate.parse(body.path("date").asText()).plusDays(bookingDateShift).toString(),
                            "status", "CHECKED_IN", "checkedInAt", Instant.now().toString());
                    CHECK_IN_RESULTS.put(key, response);
                    send(exchange, 200, response);
                }
            } else if (path.equals("/api/auth/me")) {
                send(exchange, 200, Map.of("id", wrongAccount ? 999 : actor, "role", jwt.getStringListClaim("roles").getFirst(),
                        "status", active ? "ACTIVE" : "INACTIVE"));
            } else if (path.startsWith("/api/admin/branches/") || path.startsWith("/api/staff/branches/")) {
                long branch = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                send(exchange, 200, Map.of("id", branch, "adminId", branch == 7 ? 1 : 2,
                        "status", branchActive ? "ACTIVE" : "INACTIVE"));
            } else if (path.equals("/api/internal/staff-accounts")) {
                CREATES.incrementAndGet();
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                var account = ACCOUNTS.computeIfAbsent(key, ignored -> Map.of("id", 100L, "email", body.path("email").asText(),
                        "fullName", body.path("fullName").asText(), "phone", body.path("phone").asText(),
                        "role", "STAFF", "status", "ACTIVE"));
                send(exchange, 201, account);
            } else if (path.startsWith("/api/internal/staff-accounts/")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                boolean delete = exchange.getRequestMethod().equals("DELETE");
                JsonNode body = delete ? JSON.createObjectNode() : JSON.readTree(exchange.getRequestBody());
                send(exchange, 200, Map.of("id", id, "email", "staff@example.com", "fullName", body.path("fullName").asText("Staff"),
                        "phone", body.path("phone").asText("0912345678"), "role", "STAFF", "status", delete ? "INACTIVE" : "ACTIVE"));
            } else send(exchange, 404, Map.of());
        } catch (Exception error) { send(exchange, 500, Map.of("error", "test boundary failed")); }
    }

    static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
