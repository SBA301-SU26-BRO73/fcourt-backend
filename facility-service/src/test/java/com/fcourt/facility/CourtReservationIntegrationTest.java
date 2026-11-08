package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.facility.api.ReservationDtos.ReserveRequest;
import com.fcourt.facility.api.ReservationDtos.State;
import com.fcourt.facility.integration.SlotAvailabilityClient;
import com.fcourt.facility.service.ReservationService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.ErrorResponseException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * SCRUM-241 reserve / commit / rollback against PostgreSQL 16 (Testcontainers, needs Docker). Kafka relay disabled;
 * discovery occupancy is mocked like in the other lock tests.
 */
@SpringBootTest(properties = {
        "facility.subscription.default-max-branches=3",
        "facility.subscription.default-max-courts=4",
        "facility.events.enabled=false",
        "facility.reservations.internal-token=" + CourtReservationIntegrationTest.TOKEN,
        "facility.reservations.sweep-delay-ms=3600000"})
@AutoConfigureMockMvc
class CourtReservationIntegrationTest {
    static final String TOKEN = "integration-test-reservation-token-0123456789";
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean SlotAvailabilityClient availability;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationService reservations;

    static JwtRequestPostProcessor as(long userId, String role) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static String branchBody(String name, String open, String close) {
        return """
                {"name":"%s","address":"123 Nguyen Trai","ward":"Ben Thanh","district":"Quan 1","city":"Ho Chi Minh",
                 "latitude":10.762622,"longitude":106.660172,"phone":"0901234567","openTime":"%s","closeTime":"%s",
                 "bankName":"Vietcombank","bankBin":"970436","bankAccountNumber":"0123456789","bankAccountName":"NGUYEN VAN A"}
                """.formatted(name, open, close);
    }

    static String courtBody(String name, String price) {
        return """
                {"name":"%s","courtTypeId":6,"description":"Indoor","imageUrl":"https://example.com/c.jpg","defaultPricePerHour":%s}
                """.formatted(name, price);
    }

    record Venue(long owner, long branch, long courtA, long courtB, LocalDate monday) { }

    long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    ResultActions admin(String url, String body, long owner) throws Exception {
        return mvc.perform(post(url).with(as(owner, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Branch with two courts, 08:00-10:00 slots on Mondays. */
    Venue venue(long owner, String name) throws Exception {
        Mockito.when(availability.conflicts(Mockito.anyLong(), Mockito.any())).thenReturn(List.of());
        long branch = id(admin("/api/admin/branches", branchBody(name, "06:00", "22:00"), owner)
                .andExpect(status().isCreated()));
        String courts = "/api/admin/branches/" + branch + "/courts";
        long a = id(admin(courts, courtBody("A", "100000"), owner).andExpect(status().isCreated()));
        long b = id(admin(courts, courtBody("B", "100000"), owner).andExpect(status().isCreated()));
        for (long court : List.of(a, b)) {
            admin("/api/admin/courts/" + court + "/apply-template",
                    "{\"startTime\":\"08:00\",\"endTime\":\"10:00\",\"price\":90000,\"daysOfWeek\":[1]}", owner)
                    .andExpect(status().isOk());
        }
        return new Venue(owner, branch, a, b, LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
    }

    String body(UUID id, Venue venue, String from, String to, long... courts) {
        String ids = java.util.Arrays.stream(courts).mapToObj(Long::toString).reduce((x, y) -> x + "," + y).orElseThrow();
        return """
                {"reservationId":"%s","branchId":%d,"adminId":%d,"courtIds":[%s],
                 "startAt":"%sT%s:00","endAt":"%sT%s:00","publicReason":"Giai dau"}
                """.formatted(id, venue.branch(), venue.owner(), ids, venue.monday(), from, venue.monday(), to);
    }

    ResultActions call(String method, String path, String body) throws Exception {
        var request = "GET".equals(method) ? get(path) : post(path);
        request.header("X-Internal-Service-Token", TOKEN);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    ResultActions slots(long court, Venue venue) throws Exception {
        return mvc.perform(get("/api/internal/courts/{id}/slot-schedule", court).param("date", venue.monday().toString()));
    }

    @Test
    void reserveCommitRollbackLifecycleLocksEveryCourtAndStaysIdempotent() throws Exception {
        Venue venue = venue(9401, "Lifecycle");
        UUID id = UUID.randomUUID();
        String reserve = body(id, venue, "08:00", "09:00", venue.courtA(), venue.courtB());

        call("POST", "/api/internal/court-reservations", reserve).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RESERVED"))
                .andExpect(jsonPath("$.courtIds", hasSize(2)))
                .andExpect(jsonPath("$.expiresAt").exists());
        for (long court : List.of(venue.courtA(), venue.courtB())) {
            slots(court, venue).andExpect(jsonPath("$.slots[0].outOfService").value(true))
                    .andExpect(jsonPath("$.slots[1].outOfService").value(true))
                    .andExpect(jsonPath("$.slots[1].restriction.type").value("PRIVATE_EVENT"))
                    .andExpect(jsonPath("$.slots[2].outOfService").value(false));
        }

        // Replay: same id and payload is a 200 with no extra locks; another payload is refused.
        call("POST", "/api/internal/court-reservations", reserve).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESERVED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_locks WHERE reservation_id = ?::uuid", Integer.class, id.toString()))
                .isEqualTo(2);
        call("POST", "/api/internal/court-reservations", body(id, venue, "08:00", "09:30", venue.courtA(), venue.courtB()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_MISMATCH"));
        // Another reservation id on overlapping slots conflicts; a disjoint window is fine.
        call("POST", "/api/internal/court-reservations",
                body(UUID.randomUUID(), venue, "08:30", "09:30", venue.courtA())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_OVERLAP"));
        call("POST", "/api/internal/court-reservations",
                body(UUID.randomUUID(), venue, "09:00", "10:00", venue.courtA())).andExpect(status().isCreated());

        // The admin lock API can neither see through nor delete a reservation lock.
        long lockId = jdbc.queryForObject("SELECT id FROM court_locks WHERE reservation_id = ?::uuid LIMIT 1", Long.class, id.toString());
        mvc.perform(delete("/api/admin/courts/lock-slots/{id}", lockId).with(as(venue.owner(), "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/courts/out-of-service/{group}", id).with(as(venue.owner(), "ADMIN")))
                .andExpect(status().isNotFound());

        // Commit is idempotent and clears the lease.
        call("POST", "/api/internal/court-reservations/" + id + "/commit", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMITTED")).andExpect(jsonPath("$.committedAt").exists());
        call("POST", "/api/internal/court-reservations/" + id + "/commit", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMITTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_locks WHERE reservation_id = ?::uuid AND expires_at IS NOT NULL",
                Integer.class, id.toString())).isZero();
        // Ordinary lock API sees the committed package as an overlap.
        admin("/api/admin/courts/lock-slots", """
                {"courtId":%d,"startAt":"%sT08:00:00","endAt":"%sT08:30:00","reason":"Maintenance"}
                """.formatted(venue.courtB(), venue.monday(), venue.monday()), venue.owner())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCK_OVERLAP"));

        // Rollback (compensation) releases everything and is idempotent; the id cannot be reused.
        call("POST", "/api/internal/court-reservations/" + id + "/rollback", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED")).andExpect(jsonPath("$.cancelCause").value("ROLLBACK"));
        call("POST", "/api/internal/court-reservations/" + id + "/rollback", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        slots(venue.courtB(), venue).andExpect(jsonPath("$.slots[0].outOfService").value(false))
                .andExpect(jsonPath("$.slots[1].outOfService").value(false));
        call("POST", "/api/internal/court-reservations", reserve).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CLOSED"));
        call("POST", "/api/internal/court-reservations/" + id + "/commit", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CLOSED"));
        call("GET", "/api/internal/court-reservations/" + id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        call("GET", "/api/internal/court-reservations/" + UUID.randomUUID(), null).andExpect(status().isNotFound());
    }

    @Test
    void parallelReservesOnOverlappingSlotsHaveExactlyOneWinner() throws Exception {
        Venue venue = venue(9402, "Race");
        int contenders = 8;
        var pool = Executors.newFixedThreadPool(contenders);
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        var won = new AtomicInteger();
        var lost = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        LocalDateTime start = venue.monday().atTime(8, 0);
        for (int n = 0; n < contenders; n++) {
            // Staggered but overlapping windows on the same courts: every pair collides.
            LocalDateTime from = start.plusMinutes(30L * (n % 2));
            var request = new ReserveRequest(UUID.randomUUID(), venue.branch(), venue.owner(),
                    List.of(venue.courtA(), venue.courtB()), from, from.plusMinutes(60), null, null);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    reservations.reserve(request);
                    won.incrementAndGet();
                } catch (ErrorResponseException conflict) {
                    if (conflict.getStatusCode().value() == 409) lost.incrementAndGet();
                    else throw conflict;
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) future.get();
        pool.shutdown();

        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(contenders - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_locks WHERE branch_id = ? AND reservation_id IS NOT NULL",
                Integer.class, venue.branch())).isEqualTo(2);
    }

    @Test
    void parallelRetriesOfOneReservationIdCreateItOnce() throws Exception {
        Venue venue = venue(9403, "Retry");
        UUID id = UUID.randomUUID();
        int callers = 6;
        var pool = Executors.newFixedThreadPool(callers);
        var go = new CountDownLatch(1);
        var created = new AtomicInteger();
        var request = new ReserveRequest(id, venue.branch(), venue.owner(), List.of(venue.courtA()),
                venue.monday().atTime(8, 0), venue.monday().atTime(9, 0), null, null);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int n = 0; n < callers; n++) {
            futures.add(pool.submit(() -> {
                go.await();
                return reservations.reserve(request).created();
            }));
        }
        go.countDown();
        for (Future<Boolean> future : futures) if (future.get()) created.incrementAndGet();
        pool.shutdown();

        assertThat(created.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_locks WHERE reservation_id = ?::uuid", Integer.class, id.toString()))
                .isEqualTo(1);
    }

    @Test
    void expiredLeaseIsInvisibleImmediatelyRefusesCommitAndIsSweptWithoutTouchingCommittedOnes() throws Exception {
        Venue venue = venue(9404, "Lease");
        UUID stale = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        call("POST", "/api/internal/court-reservations", body(stale, venue, "08:00", "09:00", venue.courtA()))
                .andExpect(status().isCreated());
        call("POST", "/api/internal/court-reservations", body(kept, venue, "09:00", "10:00", venue.courtA()))
                .andExpect(status().isCreated());
        call("POST", "/api/internal/court-reservations/" + kept + "/commit", null).andExpect(status().isOk());

        // Age the stale lease (and, to prove commit is protected, the committed one has no lease at all).
        jdbc.update("UPDATE court_reservations SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", stale.toString());
        jdbc.update("UPDATE court_locks SET expires_at = now() - interval '1 minute' WHERE reservation_id = ?::uuid", stale.toString());

        // Readers ignore the expired lease before any sweep; the committed one still blocks.
        slots(venue.courtA(), venue).andExpect(jsonPath("$.slots[0].outOfService").value(false))
                .andExpect(jsonPath("$.slots[2].outOfService").value(true));
        call("POST", "/api/internal/court-reservations/" + stale + "/commit", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
        call("GET", "/api/internal/court-reservations/" + stale, null).andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelCause").value("EXPIRED"));

        UUID second = UUID.randomUUID();
        call("POST", "/api/internal/court-reservations", body(second, venue, "08:00", "09:00", venue.courtA()))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE court_reservations SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", second.toString());
        jdbc.update("UPDATE court_locks SET expires_at = now() - interval '1 minute' WHERE reservation_id = ?::uuid", second.toString());
        assertThat(reservations.expireDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM court_reservations WHERE id = ?::uuid", String.class, second.toString()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT state FROM court_reservations WHERE id = ?::uuid", String.class, kept.toString()))
                .isEqualTo(State.COMMITTED.name());
        assertThat(reservations.expireDue()).isZero();
    }

    @Test
    void rollbackBeforeReserveLeavesATombstoneAndForeignOrConflictingRequestsStoreNothing() throws Exception {
        Venue venue = venue(9405, "Edges");
        UUID early = UUID.randomUUID();
        call("POST", "/api/internal/court-reservations/" + early + "/rollback", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        call("POST", "/api/internal/court-reservations", body(early, venue, "08:00", "09:00", venue.courtA()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_CLOSED"));

        UUID foreign = UUID.randomUUID();
        call("POST", "/api/internal/court-reservations",
                body(foreign, venue, "08:00", "09:00", venue.courtA()).replace("\"adminId\":9405", "\"adminId\":1"))
                .andExpect(status().isNotFound());
        call("POST", "/api/internal/court-reservations", body(foreign, venue, "08:00", "09:00", 987654321L))
                .andExpect(status().isNotFound());

        Mockito.when(availability.conflicts(venue.courtA(), venue.monday())).thenReturn(List.of(
                new SlotAvailabilityClient.SlotConflict(1L, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(8, 30), "BOOKED")));
        call("POST", "/api/internal/court-reservations", body(foreign, venue, "08:00", "09:00", venue.courtA()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_CONFLICT"));
        Mockito.when(availability.conflicts(Mockito.anyLong(), Mockito.any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        call("POST", "/api/internal/court-reservations", body(foreign, venue, "09:00", "10:00", venue.courtB()))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_reservations WHERE id = ?::uuid", Integer.class, foreign.toString()))
                .isZero();
    }

    @Test
    void endpointsRequireTheServiceToken() throws Exception {
        Venue venue = venue(9406, "Auth");
        UUID id = UUID.randomUUID();
        String reserve = body(id, venue, "08:00", "09:00", venue.courtA());
        mvc.perform(post("/api/internal/court-reservations").contentType(MediaType.APPLICATION_JSON).content(reserve))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations").header("X-Internal-Service-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(reserve)).andExpect(status().isForbidden());
        // A user JWT is not a substitute for the service credential.
        mvc.perform(post("/api/internal/court-reservations").with(as(venue.owner(), "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(reserve)).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations/{id}/commit", id)).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/court-reservations/{id}/rollback", id)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM court_reservations WHERE id = ?::uuid", Integer.class, id.toString()))
                .isZero();
    }
}
