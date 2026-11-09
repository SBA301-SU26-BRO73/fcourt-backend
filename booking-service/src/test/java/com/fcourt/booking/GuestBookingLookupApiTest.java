package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"booking.events-enabled=false", "booking.guest-lookup.ip-limit=100",
        "booking.guest-lookup.code-limit=3", "booking.guest-lookup.window=1h"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuestBookingLookupApiTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private void booking(int id, Long customer, String phone, String status, String code) {
        jdbc.update("""
            INSERT INTO bookings(id,court_id,customer_id,guest_phone,date,status,total_price,booking_code,branch_id,branch_name,branch_address,branch_phone,court_name,customer_name,checkin_code,qr_image_url,receipt_image_url,payment_status)
            OVERRIDING SYSTEM VALUE VALUES (?,1,?,?,'2099-12-01',?,100000,?,7,'Thu Duc','123 Street','0900000000','Court 1','Secret Name',?,?,?,'CONFIRMED')
            """, id, customer, phone, status, code, "CHK-" + id, "http://qr/" + id, "http://receipt/" + id);
        jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (?,?,'2099-12-01','18:00','18:30',100000)", id, id);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE booking_outbox,booking_inbox,bookings CASCADE");
        booking(1, null, "0901234567", "CONFIRMED", "FC00000001");
        booking(2, null, "0901234567", "PENDING_PAYMENT", "FC00000002");
        booking(3, 10L, null, "CONFIRMED", "FC00000003");
        booking(4, null, "0901234567", "CONFIRMED", "FC00000004");
    }

    private static RequestPostProcessor ip(String address) {
        return request -> { request.setRemoteAddr(address); return request; };
    }

    private String body(String code, String phone) {
        return "{\"bookingCode\":\"" + code + "\",\"phone\":\"" + phone + "\"}";
    }

    @Test
    void matchingCodeAndPhoneReturnsMinimalViewWithCheckInCodeWhenConfirmed() throws Exception {
        mvc.perform(post("/api/public/bookings/lookup").contentType("application/json").content(body("fc-00000001", "+84901234567")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.bookingCode").value("FC00000001"))
                .andExpect(jsonPath("$.checkInCode").value("CHK-1"))
                .andExpect(jsonPath("$.qrImageUrl").value("http://qr/1"))
                .andExpect(jsonPath("$.branchName").value("Thu Duc"))
                .andExpect(jsonPath("$.slots[0].startTime").value("18:00:00"))
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.receiptImageUrl").doesNotExist())
                .andExpect(jsonPath("$.branchPhone").doesNotExist())
                .andExpect(jsonPath("$.courtId").doesNotExist())
                .andExpect(jsonPath("$.refundAmount").doesNotExist());
    }

    @Test
    void checkInCodeHiddenWhenNotConfirmed() throws Exception {
        mvc.perform(post("/api/public/bookings/lookup").contentType("application/json").content(body("FC00000002", "0901234567")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.checkInCode").doesNotExist()).andExpect(jsonPath("$.qrImageUrl").doesNotExist());
    }

    @Test
    void allMismatchesAreIdenticalNotFound() throws Exception {
        String[] details = new String[3];
        String[][] cases = {{"FC00000001", "0909999999"}, {"FC99999999", "0901234567"}, {"FC00000003", "0901234567"}};
        for (int i = 0; i < cases.length; i++) {
            var result = mvc.perform(post("/api/public/bookings/lookup").contentType("application/json")
                            .content(body(cases[i][0], cases[i][1])).with(ip("10.0.0." + i)))
                    .andExpect(status().isNotFound()).andReturn();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
            details[i] = json.path("detail").asText() + json.path("title").asText() + json.path("status").asInt();
        }
        assertThat(details[0]).isEqualTo(details[1]).isEqualTo(details[2]).contains("Booking not found");
    }

    @Test
    void invalidFormatIsBadRequestAndAnonymousAllowed() throws Exception {
        for (String bad : new String[] {body("XX1", "0901234567"), body("FC00000001", "abc"), "{}",
                body("FC00000001", "")})
            mvc.perform(post("/api/public/bookings/lookup").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void perBookingCodeLimitReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++)
            mvc.perform(post("/api/public/bookings/lookup").contentType("application/json")
                    .content(body("FC00000004", "0900000001")).with(ip("10.1.0." + i))).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/bookings/lookup").contentType("application/json")
                        .content(body("FC00000004", "0901234567")).with(ip("10.1.9.9")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
