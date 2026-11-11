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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "booking.events-enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuestBookingCancelApiTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private void booking(int id, Long customer, String phone, String status, String payment, String code, String date) {
        jdbc.update("""
            INSERT INTO bookings(id,court_id,customer_id,guest_phone,date,status,total_price,booking_code,branch_id,branch_name,branch_address,branch_phone,court_name,customer_name,checkin_code,qr_image_url,receipt_image_url,payment_status)
            OVERRIDING SYSTEM VALUE VALUES (?,?,?,?,?::date,?,100000,?,7,'Thu Duc','123 Street','0900000000','Court 1','Name',?,?,?,?)
            """, id, id, customer, phone, date, status, code, "CHK-" + id, "http://qr/" + id, "http://receipt/" + id, payment);
        jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (?,?,?::date,'18:00','18:30',100000)", id, id, date);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE booking_outbox,booking_inbox,bookings CASCADE");
        booking(1, null, "0901234567", "PENDING_PAYMENT", "PENDING", "FC00000001", "2099-12-01");
        booking(2, null, "0901234567", "CONFIRMED", "CONFIRMED", "FC00000002", "2099-12-01");
        booking(3, 10L, null, "CONFIRMED", "CONFIRMED", "FC00000003", "2099-12-01");
        booking(4, null, "0901234567", "CONFIRMED", "CONFIRMED", "FC00000004", "2000-01-01");
        booking(5, null, "0901234567", "COMPLETED", "CONFIRMED", "FC00000005", "2099-12-01");
    }

    private String body(String code, String phone) {
        return "{\"bookingCode\":\"" + code + "\",\"phone\":\"" + phone + "\"}";
    }

    private String statusOf(int id) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, id);
    }

    @Test
    void unpaidGuestBookingIsCancelledWithoutRefundAndSlotsReleased() throws Exception {
        mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body("fc-00000001", "+84901234567")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.bookingCode").value("FC00000001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value(0));
        assertThat(statusOf(1)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void paidGuestBookingReturnsRefundAndEmitsCancelledEvent() throws Exception {
        mvc.perform(post("/api/public/bookings/cancel").contentType("application/json")
                        .content("{\"bookingCode\":\"FC00000002\",\"phone\":\"0901234567\",\"reason\":\"Bận việc\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(100000));
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=2", String.class)).isEqualTo("Bận việc");
        assertThat(jdbc.queryForObject("SELECT payload->>'type' FROM booking_outbox", String.class)).isEqualTo("BookingCancelledEvent");
    }

    @Test
    void unpaidGuestBookingInsideCutoffIsCancelledAnyTime() throws Exception {
        booking(6, null, "0901234567", "PENDING_PAYMENT", "PENDING", "FC00000006", "2000-01-01");
        jdbc.update("UPDATE bookings SET receipt_image_url=NULL WHERE id=6");
        mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body("FC00000006", "0901234567")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.refundAmount").value(0));
        assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=6", Boolean.class)).isFalse();
    }

    @Test
    void awaitingConfirmationWithReceiptInsideCutoffIsRejected() throws Exception {
        booking(7, null, "0901234567", "AWAITING_CONFIRMATION", "PENDING", "FC00000007", "2000-01-01");
        mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body("FC00000007", "0901234567")))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(7)).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void repeatedCancelIsIdempotentAndCreatesOneEvent() throws Exception {
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body("FC00000002", "0901234567")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongPhoneUnknownCodeAndAccountBookingAreIdenticalNotFoundAndChangeNothing() throws Exception {
        String[][] cases = {{"FC00000002", "0909999999"}, {"FC99999999", "0901234567"}, {"FC00000003", "0901234567"}};
        for (String[] c : cases)
            mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body(c[0], c[1])))
                    .andExpect(status().isNotFound());
        assertThat(statusOf(2)).isEqualTo("CONFIRMED");
        assertThat(statusOf(3)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox", Integer.class)).isZero();
    }

    @Test
    void pastCutoffAndNonCancellableStatusAreRejected() throws Exception {
        for (String code : new String[] {"FC00000004", "FC00000005"})
            mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(body(code, "0901234567")))
                    .andExpect(status().isBadRequest());
        assertThat(statusOf(4)).isEqualTo("CONFIRMED");
        assertThat(statusOf(5)).isEqualTo("COMPLETED");
    }

    @Test
    void invalidFormatIsBadRequestAndAnonymousAllowed() throws Exception {
        for (String bad : new String[] {body("XX1", "0901234567"), body("FC00000001", "abc"), "{}",
                "{\"bookingCode\":\"FC00000001\",\"phone\":\"0901234567\",\"reason\":\"" + "x".repeat(501) + "\"}"})
            mvc.perform(post("/api/public/bookings/cancel").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
    }
}
