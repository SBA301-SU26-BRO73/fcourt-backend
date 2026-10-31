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
import org.junit.jupiter.params.provider.EnumSource;
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
    static final AtomicBoolean WALK_IN_OCCUPIED = new AtomicBoolean();
    static final AtomicBoolean COMPLETED = new AtomicBoolean();
    static final Map<String, Map<String, Object>> WALK_IN_RESULTS = new ConcurrentHashMap<>();
    static final Map<String, String> WALK_IN_REQUESTS = new ConcurrentHashMap<>();
    static final Map<String, Map<String, Object>> CHECKOUT_RESULTS = new ConcurrentHashMap<>();
    static final java.util.Queue<String> BOUNDARY_CALLS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static volatile String slotStatus = "AVAILABLE";
    static volatile boolean foreignCourt;
    static volatile boolean wrongCashier;
    static volatile boolean finalClaimConflict;
    static volatile boolean bulkSchedule;
    static volatile int bookingFailure;
    static volatile boolean wrongBranch;
    static volatile int bookingDateShift;
    static volatile int failure;
    static volatile boolean active = true;
    static volatile boolean branchActive = true;
    static volatile boolean malformed;
    static volatile boolean wrongAccount;
    static final HttpServer UPSTREAM = server();
    static final SlotSafetyFixture SLOT_SAFETY = new SlotSafetyFixture();
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
        registry.add("staff.discovery.url", () -> URL);
        registry.add("staff.grpc.hold-target", SLOT_SAFETY::target);
        registry.add("staff.grpc.booking-target", SLOT_SAFETY::target);
        registry.add("staff.grpc.plaintext", () -> true);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE staff RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE staff_booking_commands, staff_outbox");
        CHECK_IN_RESULTS.clear();
        CHECKED_IN.set(false);
        WALK_IN_OCCUPIED.set(false);
        COMPLETED.set(false);
        WALK_IN_RESULTS.clear();
        WALK_IN_REQUESTS.clear();
        CHECKOUT_RESULTS.clear();
        BOUNDARY_CALLS.clear();
        slotStatus = "AVAILABLE";
        foreignCourt = false;
        wrongCashier = false;
        finalClaimConflict = false;
        bulkSchedule = false;
        SLOT_SAFETY.reset();
        bookingFailure = bookingDateShift = 0;
        wrongBranch = false;
        ACCOUNTS.clear();
        CREATES.set(0);
        failure = 0;
        active = branchActive = true;
        malformed = wrongAccount = false;
    }

    @AfterAll
    static void stop() { UPSTREAM.stop(0); SLOT_SAFETY.close(); }

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

    @Test
    void walkInCreatesCashCheckedInBookingAndCheckoutCompletesIt() {
        create(UUID.randomUUID(), body(7));
        UUID key = UUID.randomUUID();
        var result = walkIn(key, walkInBody());
        assertThat(result.getStatusCode()).as("Walk-in response: %s; boundaries: %s", result.getBody(), BOUNDARY_CALLS).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(result.getBody().path("paymentMethod").asText()).isEqualTo("CASH");
        assertThat(result.getBody().path("amount").asInt()).isEqualTo(100000);
        assertThat(walkIn(key, walkInBody()).getBody()).isEqualTo(result.getBody());
        UUID checkoutKey = UUID.randomUUID();
        var checkout = checkout(checkoutKey);
        assertThat(checkout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkout.getBody().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(checkout(checkoutKey).getBody()).isEqualTo(checkout.getBody());
        assertThat(checkout(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT payload FROM staff_outbox").toString())
                .contains("WalkInBookingCreatedEvent", "BookingCompletedEvent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOLD", "BOOKED", "OUT_OF_SERVICE", "EXPIRED"})
    void walkInCannotUseUnavailableSlot(String state) {
        create(UUID.randomUUID(), body(7));
        slotStatus = state;
        assertThat(walkIn(UUID.randomUUID(), walkInBody()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(WALK_IN_RESULTS).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isZero();
    }

    @Test
    void walkInRejectsForeignCourtOtherDateAndClientPricing() {
        create(UUID.randomUUID(), body(7));
        foreignCourt = true;
        assertThat(walkIn(UUID.randomUUID(), walkInBody()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        foreignCourt = false;
        var invalid = new java.util.HashMap<>(walkInBody());
        invalid.put("date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1).toString());
        assertThat(walkIn(UUID.randomUUID(), invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        invalid = new java.util.HashMap<>(walkInBody());
        invalid.put("amount", 1);
        assertThat(walkIn(UUID.randomUUID(), invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void walkInRecoversRemoteSuccessEvenWhenAvailabilityNowSaysBooked() {
        create(UUID.randomUUID(), body(7));
        UUID key = UUID.randomUUID();
        jdbc.execute("ALTER TABLE staff_outbox ADD CONSTRAINT reject_walk_in CHECK (booking_id <> 43)");
        try {
            assertThat(walkIn(key, walkInBody()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(WALK_IN_OCCUPIED.get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_booking_commands", Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE staff_outbox DROP CONSTRAINT reject_walk_in"); }
        var mismatched = new java.util.HashMap<>(walkInBody());
        mismatched.put("guestPhone", "0912345679");
        assertThat(walkIn(key, mismatched).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(walkIn(key, walkInBody()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(WALK_IN_RESULTS).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentWalkInsUseAtomicAuthorityAndCreateOnlyOneBooking() throws Exception {
        create(UUID.randomUUID(), body(7));
        int successful = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(ignored -> executor.submit(() -> walkIn(UUID.randomUUID(), walkInBody()))).toList();
            for (var request : requests) {
                var status = request.get().getStatusCode();
                assertThat(status).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
                if (status == HttpStatus.CREATED) successful++;
            }
        }
        assertThat(successful).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void checkoutWrongBranchOrUpstreamFailureDoesNotPublishCompletion() {
        create(UUID.randomUUID(), body(7));
        walkIn(UUID.randomUUID(), walkInBody());
        wrongBranch = true;
        assertThat(checkout(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isEqualTo(1);
        bookingFailure = 500;
        assertThat(checkout(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    ResponseEntity<JsonNode> walkIn(UUID key, Object request) {
        return call(HttpMethod.POST, "/api/staff/walk-in", request, token("100", "STAFF"), key);
    }

    ResponseEntity<JsonNode> checkout(UUID key) {
        return call(HttpMethod.PUT, "/api/staff/bookings/43/checkout", null, token("100", "STAFF"), key);
    }

    static Map<String, Object> walkInBody() {
        return Map.of("courtId", 21, "date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(),
                "slotIds", List.of(37), "guestPhone", "0912345678");
    }

    @Test
    void multipleContiguousWalkInSlotsProducePrintableCashInvoice() {
        create(UUID.randomUUID(), body(7));
        var request = new java.util.HashMap<>(walkInBody());
        request.put("slotIds", List.of(38, 37));
        var response = walkIn(UUID.randomUUID(), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("amount").asInt()).isEqualTo(200000);
        assertThat(response.getBody().path("invoice").path("receiptCode").asText()).isEqualTo("INV-WI00000043");
        assertThat(response.getBody().path("invoice").path("cashierUserId").asInt()).isEqualTo(100);
        assertThat(response.getBody().path("invoice").path("items").size()).isEqualTo(2);
        assertThat(SLOT_SAFETY.holdCalls.get()).isEqualTo(1);
        assertThat(SLOT_SAFETY.bookingCalls.get()).isEqualTo(1);
    }

    @Test
    void rejectsNonContiguousOrDuplicateSlotsBeforeCreatingBooking() {
        create(UUID.randomUUID(), body(7));
        var request = new java.util.HashMap<>(walkInBody());
        request.put("slotIds", List.of(37, 39));
        assertThat(walkIn(UUID.randomUUID(), request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        request.put("slotIds", List.of(37, 37));
        assertThat(walkIn(UUID.randomUUID(), request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(WALK_IN_RESULTS).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SlotSafetyFixture.Mode.class, names = {"HOLD_BUSY", "BOOKING_BUSY", "UNAVAILABLE", "MALFORMED"})
    void grpcSafetyMustSucceedBeforeAnyWalkInMutation(SlotSafetyFixture.Mode mode) {
        create(UUID.randomUUID(), body(7));
        SLOT_SAFETY.mode = mode;
        int expected = mode == SlotSafetyFixture.Mode.HOLD_BUSY || mode == SlotSafetyFixture.Mode.BOOKING_BUSY ? 409 : 503;
        assertThat(walkIn(UUID.randomUUID(), walkInBody()).getStatusCode().value()).isEqualTo(expected);
        assertThat(WALK_IN_RESULTS).isEmpty();
    }

    @Test
    void invalidCashierSnapshotDoesNotEmitCashSuccess() {
        create(UUID.randomUUID(), body(7));
        wrongCashier = true;
        assertThat(walkIn(UUID.randomUUID(), walkInBody()).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isZero();
    }

    @Test
    void atomicAuthorityCanRejectARaceAfterAllPreflightReadsSucceeded() {
        create(UUID.randomUUID(), body(7));
        finalClaimConflict = true;
        assertThat(walkIn(UUID.randomUUID(), walkInBody()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(SLOT_SAFETY.holdCalls.get()).isEqualTo(1);
        assertThat(SLOT_SAFETY.bookingCalls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_outbox", Integer.class)).isZero();
    }

    @Test
    void weeklyMatrixIncludesEmptyDaysAndUsesStaffBranch() {
        create(UUID.randomUUID(), body(7));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var response = call(HttpMethod.GET, "/api/staff/schedule?startDate=" + today.minusDays(1)
                + "&endDate=" + today.plusDays(5) + "&branchId=8", null, token("100", "STAFF"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("branchId").asInt()).isEqualTo(7);
        var days = response.getBody().path("courts").get(0).path("days");
        assertThat(days.size()).isEqualTo(7);
        assertThat(days.get(0).path("slots").size()).isZero();
        assertThat(days.get(1).path("slots").size()).isEqualTo(1);
        assertThat(days.get(1).path("slots").get(0).path("flags").has("lateCheckIn")).isTrue();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"startDate=2030-01-02&endDate=2030-01-01", "startDate=2030-01-01&endDate=2030-01-08",
            "startDate=bad&endDate=2030-01-01", "startDate=2030-01-01"})
    void invalidWeekRangeIsRejected(String query) {
        assertThat(call(HttpMethod.GET, "/api/staff/schedule?" + query, null, token("100", "STAFF"), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void benchmarkTenCourtsSevenDaysThroughAuthenticatedHttp() throws Exception {
        create(UUID.randomUUID(), body(7));
        bulkSchedule = true;
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String uri = "/api/staff/schedule?startDate=" + start + "&endDate=" + start.plusDays(6);
        String bearer = token("100", "STAFF");
        for (int i = 0; i < 15; i++) {
            assertThat(call(HttpMethod.GET, uri, null, bearer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        List<Double> timings = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long begin = System.nanoTime();
            var response = call(HttpMethod.GET, uri, null, bearer, null);
            timings.add((System.nanoTime() - begin) / 1_000_000.0);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("courts").size()).isEqualTo(10);
            assertThat(response.getBody().path("courts").get(0).path("days").size()).isEqualTo(7);
        }
        timings.sort(Double::compareTo);
        double p95 = timings.get((int) Math.ceil(timings.size() * 0.95) - 1);
        var report = Map.of("courts", 10, "days", 7, "slots", 2240, "warmup", 15, "samples", 30,
                "p50Ms", timings.get(15), "p95Ms", p95, "maxMs", timings.getLast(),
                "java", System.getProperty("java.version"), "processors", Runtime.getRuntime().availableProcessors());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/schedule-benchmark.json"), JSON.writeValueAsString(report));
        assertThat(p95).as("Warmed HTTP p95, 10 courts x 7 days x 32 slots").isLessThan(250.0);
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
        if (path.startsWith("/api/public/courts/")) {
            if (exchange.getRequestHeaders().containsKey("Authorization")) { send(exchange, 400, Map.of()); return; }
            String state = WALK_IN_OCCUPIED.get() ? "BOOKED" : slotStatus;
            send(exchange, 200, List.of(
                    Map.of("slotId", 37, "startTime", "18:00", "endTime", "18:30", "price", 100000, "status", state),
                    Map.of("slotId", 38, "startTime", "18:30", "endTime", "19:00", "price", 100000, "status", state),
                    Map.of("slotId", 39, "startTime", "20:00", "endTime", "20:30", "price", 100000, "status", state)));
            return;
        }
        try {
            var jwt = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet();
            long actor = Long.parseLong(jwt.getSubject());
            if (path.startsWith("/api/internal/branches/")) {
                if (bookingFailure != 0) { send(exchange, bookingFailure, Map.of()); return; }
                if (bulkSchedule) {
                    Map<String, String> query = new java.util.HashMap<>();
                    for (String item : exchange.getRequestURI().getQuery().split("&")) {
                        var parts = item.split("=", 2);
                        query.put(parts[0], java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                    }
                    LocalDate start = LocalDate.parse(query.get("startDate"));
                    LocalDate end = LocalDate.parse(query.get("endDate"));
                    var courts = new java.util.ArrayList<Map<String, Object>>();
                    for (int court = 1; court <= 10; court++) {
                        var slots = new java.util.ArrayList<Map<String, Object>>();
                        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                            for (int slot = 0; slot < 32; slot++) {
                                var begins = java.time.LocalTime.of(6, 0).plusMinutes(slot * 30L);
                                slots.add(Map.of("bookingId", court * 100000 + date.getDayOfYear() * 100 + slot,
                                        "date", date.toString(), "slotStart", begins.toString(), "slotEnd", begins.plusMinutes(30).toString(),
                                        "status", "CONFIRMED", "customerName", "Demo guest"));
                            }
                        }
                        courts.add(Map.of("courtId", court, "courtName", "Court " + court, "slots", slots));
                    }
                    send(exchange, 200, Map.of("branchId", 7, "courts", courts));
                    return;
                }
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
            } else if (path.equals("/api/internal/bookings/walk-in/reconcile") || path.equals("/api/internal/bookings/walk-in")) {
                if (bookingFailure != 0) { send(exchange, bookingFailure, Map.of()); return; }
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                synchronized (WALK_IN_RESULTS) {
                    var canonical = ((com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy());
                    canonical.remove("checkedInAt");
                    canonical.remove("quote");
                    if (WALK_IN_REQUESTS.containsKey(key) && !WALK_IN_REQUESTS.get(key).equals(canonical.toString())) {
                        send(exchange, 409, Map.of()); return;
                    }
                    if (WALK_IN_RESULTS.containsKey(key)) { send(exchange, 200, WALK_IN_RESULTS.get(key)); return; }
                    if (path.endsWith("/reconcile")) { send(exchange, 404, Map.of()); return; }
                    if (finalClaimConflict) { send(exchange, 409, Map.of()); return; }
                    if (!WALK_IN_OCCUPIED.compareAndSet(false, true)) { send(exchange, 409, Map.of()); return; }
                    JsonNode quote = body.path("quote");
                    var invoice = Map.of("receiptCode", "INV-WI00000043", "cashierUserId", wrongCashier ? actor + 1 : actor,
                            "baseAmount", quote.path("baseAmount").decimalValue(), "surchargeAmount", quote.path("surchargeAmount").decimalValue(),
                            "totalAmount", quote.path("totalAmount").decimalValue(), "policyVersion", quote.path("policyVersion").asText(),
                            "items", quote.path("items"));
                    var response = Map.<String, Object>of("bookingId", 43, "bookingCode", "WI00000043", "branchId", 7,
                            "courtId", body.path("courtId").asLong(), "date", body.path("date").asText(), "status", "CHECKED_IN",
                            "paymentMethod", "CASH", "amount", quote.path("totalAmount").decimalValue(), "checkedInAt", Instant.now().toString(),
                            "invoice", invoice);
                    WALK_IN_RESULTS.put(key, response);
                    WALK_IN_REQUESTS.put(key, canonical.toString());
                    send(exchange, 201, response);
                }
            } else if (path.equals("/api/internal/bookings/43/checkout")) {
                if (bookingFailure != 0) { send(exchange, bookingFailure, Map.of()); return; }
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                synchronized (CHECKOUT_RESULTS) {
                    if (CHECKOUT_RESULTS.containsKey(key)) { send(exchange, 200, CHECKOUT_RESULTS.get(key)); return; }
                    if (!WALK_IN_OCCUPIED.get() || !COMPLETED.compareAndSet(false, true)) { send(exchange, 409, Map.of()); return; }
                    var response = Map.<String, Object>of("bookingId", 43, "branchId", wrongBranch ? 8 : 7,
                            "status", "COMPLETED", "completedAt", Instant.now().toString());
                    CHECKOUT_RESULTS.put(key, response);
                    send(exchange, 200, response);
                }
            } else if (path.startsWith("/api/staff/courts/")) {
                send(exchange, 200, Map.of("id", 21, "branchId", foreignCourt ? 8 : 7, "status", "ACTIVE"));
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
        BOUNDARY_CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + status);
        byte[] body = JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
