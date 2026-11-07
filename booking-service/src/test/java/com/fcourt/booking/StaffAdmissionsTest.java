package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;

@Testcontainers
@SpringBootTest(
        properties = {
            "booking.events-enabled=false",
            "STAFF_BOOKING_SERVICE_TOKEN=fixture-staff-booking-token-not-production"
        })
@AutoConfigureMockMvc
class StaffAdmissionsTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Time {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock staffTestClock() {
            return Clock.fixed(Instant.parse("2026-11-01T02:15:00Z"), ZoneOffset.UTC);
        }
    }

    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean StaffAuthority authority;
    LocalDate date;

    @BeforeEach
    void seed() {
        date = LocalDate.of(2026, 11, 1);
        jdbc.execute("TRUNCATE booking_staff_commands,bookings CASCADE");
        jdbc.update(
                "INSERT INTO"
                    + " bookings(id,court_id,guest_phone,date,status,total_price,booking_code,branch_id,court_name,customer_name,checkin_code)"
                    + " OVERRIDING SYSTEM VALUE VALUES"
                    + " (42,21,'0912345678',?,'CONFIRMED',100000,'FC89234',7,'Court"
                    + " 1','Guest','ticket')",
                date);
        jdbc.update(
                "INSERT INTO"
                    + " booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking)"
                    + " VALUES (42,21,?,'09:00','09:30',100000)",
                date);
    }

    @Test
    void previewAndRosterAreReadOnlyAndBranchScoped() throws Exception {
        mvc.perform(
                        post("/api/internal/bookings/check-in/preview")
                                .with(jwt().jwt(j -> j.subject("100")))
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "actorId",
                                                        100,
                                                        "branchId",
                                                        7,
                                                        "date",
                                                        date.toString(),
                                                        "checkInCode",
                                                        "ticket"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(42));
        mvc.perform(
                        get("/api/internal/branches/7/offline-tickets?date=" + date)
                                .with(jwt().jwt(j -> j.subject("100"))))
                .andExpect(jsonPath("$.tickets.length()").value(1));
        mvc.perform(
                        get("/api/internal/branches/8/offline-tickets?date=" + date)
                                .with(jwt().jwt(j -> j.subject("100"))))
                .andExpect(jsonPath("$.tickets.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=42", String.class))
                .isEqualTo("CONFIRMED");
    }

    Map<String, Object> command() {
        return Map.of(
                "actorId",
                100,
                "branchId",
                7,
                "bookingId",
                42,
                "date",
                date.toString(),
                "checkedInAt",
                date.atTime(9, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toString());
    }

    @Test
    void offlineRequiresServiceCredentialAndAtomicReplay() throws Exception {
        // Use yesterday's booked date/time so the server clock never makes the fixture
        // future-dated.
        date = date.minusDays(1);
        jdbc.update("UPDATE bookings SET date=? WHERE id=42", date);
        jdbc.update("UPDATE booking_slots SET booking_date=? WHERE booking_id=42", date);
        var body = json.writeValueAsString(command());
        String key = "staff:offline:100:" + UUID.randomUUID();
        mvc.perform(
                        post("/api/internal/bookings/offline-check-in")
                                .with(jwt().jwt(j -> j.subject("100")))
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/internal/bookings/offline-check-in")
                                    .with(jwt().jwt(j -> j.subject("100")))
                                    .header("Idempotency-Key", key)
                                    .header(
                                            "X-Staff-Service-Token",
                                            "fixture-staff-booking-token-not-production")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CHECKED_IN"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_staff_commands", Integer.class))
                .isEqualTo(1);
        mvc.perform(
                        post("/api/internal/bookings/offline-check-in")
                                .with(jwt().jwt(j -> j.subject("100")))
                                .header("Idempotency-Key", "staff:offline:100:" + UUID.randomUUID())
                                .header(
                                        "X-Staff-Service-Token",
                                        "fixture-staff-booking-token-not-production")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void gappedSlotsAndUnconfirmedTicketsCannotProduceOfflinePermits() throws Exception {
        jdbc.update(
                "INSERT INTO"
                    + " booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking)"
                    + " VALUES (42,21,?,'10:00','10:30',100000)",
                date);
        mvc.perform(
                        get("/api/internal/branches/7/offline-tickets?date=" + date)
                                .with(jwt().jwt(j -> j.subject("100"))))
                .andExpect(jsonPath("$.tickets.length()").value(0));
        jdbc.update("DELETE FROM booking_slots WHERE slot_start='10:00'");
        jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=42");
        mvc.perform(
                        get("/api/internal/branches/7/offline-tickets?date=" + date)
                                .with(jwt().jwt(j -> j.subject("100"))))
                .andExpect(jsonPath("$.tickets.length()").value(0));
    }

    @Test
    void manualPrintedCodeAndQrCompeteForOneAdmission() throws Exception {
        String key = "staff:check-in:100:" + UUID.randomUUID();
        var request =
                Map.of(
                        "branchId",
                        7,
                        "actorId",
                        100,
                        "date",
                        date.toString(),
                        "checkedInAt",
                        "2026-11-01T02:15:00Z",
                        "checkInCode",
                        "FC-89234",
                        "channel",
                        "MANUAL");
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/internal/bookings/check-in")
                                    .with(jwt().jwt(j -> j.subject("100")))
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingId").value(42));
        var qr = new HashMap<>(request);
        qr.put("channel", "QR");
        qr.put("checkInCode", "ticket");
        mvc.perform(
                        post("/api/internal/bookings/check-in")
                                .with(jwt().jwt(j -> j.subject("100")))
                                .header(
                                        "Idempotency-Key",
                                        "staff:check-in:100:" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(qr)))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_staff_commands", Integer.class))
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "00000000000000000000000000000001",
        "12345678901234567890123456789012"
    })
    void manualAcceptsExactNumericTicketAndReplaysOnce(String code) throws Exception {
        jdbc.update("UPDATE bookings SET checkin_code=? WHERE id=42", code);
        String key = "staff:check-in:100:" + UUID.randomUUID();
        var request = new HashMap<String, Object>();
        request.put("branchId", 7);
        request.put("actorId", 100);
        request.put("date", date.toString());
        request.put("checkedInAt", "2026-11-01T02:15:00Z");
        request.put("checkInCode", "  " + code + "  ");
        request.put("channel", "MANUAL");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/internal/bookings/check-in")
                            .with(jwt().jwt(j -> j.subject("100")))
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingId").value(42))
                    .andExpect(jsonPath("$.status").value("CHECKED_IN"));
        }
        request.put("checkInCode", code);
        request.put("channel", "QR");
        mvc.perform(post("/api/internal/bookings/check-in")
                        .with(jwt().jwt(j -> j.subject("100")))
                        .header("Idempotency-Key", "staff:check-in:100:" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_staff_commands", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=42", String.class))
                .isEqualTo("CHECKED_IN");
    }
}
