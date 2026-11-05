package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
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
class AdminBookingApiTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicReference<String> BRANCHES = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger FACILITY_STATUS = new AtomicInteger(200);
    private static final HttpServer FACILITY = startFacility();

    private static HttpServer startFacility() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/admin/branches", exchange -> {
                CALLS.incrementAndGet();
                byte[] bytes = BRANCHES.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(FACILITY_STATUS.get(), bytes.length);
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

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("100").claim("roles", List.of("ADMIN")));
    }

    @BeforeEach
    void seed() {
        CALLS.set(0);
        FACILITY_STATUS.set(200);
        BRANCHES.set("[{\"id\":7,\"adminId\":100},{\"id\":8,\"adminId\":100}]");
        jdbc.execute("TRUNCATE bookings CASCADE");
        Object[][] rows = {
                {1, 7, 11, "Nguyen An", "090111", "2030-01-10", "CONFIRMED", "CONFIRMED", 3},
                {2, 7, 12, "Tran Binh", "090222", "2030-01-11", "PENDING_PAYMENT", "PENDING", 2},
                {3, 8, 21, "nguyen an", "090333", "2030-01-10", "CANCELLED", "REJECTED", 4},
                {4, 9, 31, "Nguyen An", "090111", "2030-01-10", "CONFIRMED", "CONFIRMED", 5},
                {5, 7, 11, "Percent%_!", "090555", "2030-01-12", "COMPLETED", "PAID", 1}
        };
        for (Object[] row : rows) {
            jdbc.update("""
                    INSERT INTO bookings(id,branch_id,court_id,customer_id,customer_name,guest_phone,date,status,
                        payment_status,created_at,total_price,booking_code,branch_name,court_name,hold_token_hash,checkin_code)
                    OVERRIDING SYSTEM VALUE VALUES (?,?,?,100,?,?,?::date,?,?,?::timestamptz,125000.50,?,'Branch','Court','secret-hash',?)
                    """, row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7],
                    "2029-01-0" + row[8] + "T10:00:00Z", "ADMIN0000" + row[0], "secret-code-" + row[0]);
        }
    }

    @Test
    void combinedCriteriaReturnOnlyOwnedMatchesAndRedactSecrets() throws Exception {
        mvc.perform(get("/api/admin/bookings").with(admin()).param("branchId", "7").param("courtId", "11")
                        .param("fromDate", "2030-01-10").param("toDate", "2030-01-10")
                        .param("status", "CONFIRMED").param("paymentStatus", "CONFIRMED").param("search", " NGUYEN "))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].courtId").value(11))
                .andExpect(jsonPath("$.items[0].customerPhone").value("090111"))
                .andExpect(jsonPath("$.items[0].totalPrice").value(125000.50))
                .andExpect(jsonPath("$.items[0].holdTokenHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].checkInCode").doesNotExist());
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void deniesAnonymousForeignRolesAndUnownedBranchWithoutLeakingRows() throws Exception {
        mvc.perform(get("/api/admin/bookings").header("X-User-Id", "100")).andExpect(status().isUnauthorized());
        for (String role : List.of("CUSTOMER", "STAFF", "SUPER_ADMIN")) {
            mvc.perform(get("/api/admin/bookings").with(jwt().jwt(token -> token.subject("100").claim("roles", List.of(role)))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/bookings").with(jwt().jwt(token -> token.subject("abc").claim("roles", List.of("ADMIN")))))
                .andExpect(status().isUnauthorized());
        assertThat(CALLS.get()).isZero();
        mvc.perform(get("/api/admin/bookings?branchId=9").with(admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/bookings?courtId=31").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/admin/bookings?search=090111").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
    }
}
