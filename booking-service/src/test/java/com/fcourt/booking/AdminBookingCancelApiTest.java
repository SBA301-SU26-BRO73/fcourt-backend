package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "booking.events-enabled=false")
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
class AdminBookingCancelApiTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final HttpServer FACILITY = startFacility();

    private static HttpServer startFacility() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/admin/branches", exchange -> {
                byte[] bytes = "[{\"id\":7,\"adminId\":100}]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("booking.facility-url", () -> "http://127.0.0.1:" + FACILITY.getAddress().getPort());
    }

    @AfterAll static void stop() { FACILITY.stop(0); }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("100").claim("roles", List.of("ADMIN")));
    }

    private void booking(int id, long branch, String status, String payment) {
        jdbc.update("""
            INSERT INTO bookings(id,court_id,customer_id,date,status,total_price,booking_code,branch_id,branch_name,branch_address,court_name,payment_status,payment_id)
            OVERRIDING SYSTEM VALUE VALUES (?,1,10,'2099-12-01',?,200000,?,?,'Thu Duc','123 Street','Court 1',?,42)
            """, id, status, "ADMIN0000" + id, branch, payment);
        jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (?,?,'2099-12-01','18:00','18:30',200000)", id, id);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE booking_outbox,booking_inbox,bookings CASCADE");
        booking(1, 7, "CONFIRMED", "CONFIRMED");
        booking(2, 9, "CONFIRMED", "CONFIRMED");
        booking(3, 7, "CHECKED_IN", "CONFIRMED");
        booking(4, 7, "PENDING_PAYMENT", "PENDING");
        booking(5, 7, "COMPLETED", "CONFIRMED");
    }

    private org.springframework.test.web.servlet.ResultActions cancel(int id, String reason, String key) throws Exception {
        var request = post("/api/admin/bookings/" + id + "/cancel").with(admin()).contentType("application/json")
                .content("{\"reason\":" + json.writeValueAsString(reason) + "}");
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    @Test
    void paidBookingIsCancelledWithFullRefundAndSameEventAsCustomerCancel() throws Exception {
        cancel(1, " Court flooded ", "k1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(200000))
                .andExpect(jsonPath("$.cancellationReason").value("Court flooded"));
        var event = json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox", String.class));
        assertThat(event.path("type").asText()).isEqualTo("BookingCancelledEvent");
        assertThat(event.path("booking").path("refundAmount").asInt()).isEqualTo(200000);
        assertThat(event.path("booking").path("paymentId").asInt()).isEqualTo(42);
        assertThat(event.path("booking").path("cancelledByRole").asText()).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM bookings WHERE id=1", Long.class)).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT cancelled_by_role FROM bookings WHERE id=1", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void repeatingCancelIsIdempotentAndEmitsOneEvent() throws Exception {
        cancel(1, "x", "k1").andExpect(status().isOk());
        cancel(1, "x", "k1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void unpaidBookingHasZeroRefund() throws Exception {
        cancel(4, "x", "k").andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(0));
    }

    @Test
    void checkedInAndCompletedAreConflicts() throws Exception {
        cancel(3, "x", "k").andExpect(status().isConflict());
        cancel(5, "x", "k").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class)).isZero();
    }

    @Test
    void foreignBranchAndMissingBookingAreNotFound() throws Exception {
        cancel(2, "x", "k").andExpect(status().isNotFound());
        cancel(999, "x", "k").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=2", String.class)).isEqualTo("CONFIRMED");
    }

    @Test
    void packageMemberMustUseFullCourtCancel() throws Exception {
        jdbc.execute("ALTER TABLE full_court_package_bookings DROP CONSTRAINT IF EXISTS full_court_package_bookings_package_id_fkey");
        jdbc.update("INSERT INTO full_court_package_bookings(package_id,court_id,booking_id) VALUES (gen_random_uuid(),1,1)");
        cancel(1, "x", "k").andExpect(status().isConflict());
    }

    @Test
    void validationAndAuthorization() throws Exception {
        cancel(1, " ", "k").andExpect(status().isBadRequest());
        cancel(1, "a".repeat(501), "k").andExpect(status().isBadRequest());
        cancel(1, "x", null).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/bookings/1/cancel").header("Idempotency-Key", "k").contentType("application/json")
                .content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/bookings/1/cancel").with(jwt().jwt(t -> t.subject("100").claim("roles", List.of("CUSTOMER"))))
                .header("Idempotency-Key", "k").contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
