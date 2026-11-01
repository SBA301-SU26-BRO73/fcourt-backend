package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties="booking.events-enabled=false")
@AutoConfigureMockMvc
class BookingApiTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("booking_db");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE booking_inbox,bookings CASCADE");
        for(int i=1;i<=4;i++) jdbc.update("""
          INSERT INTO bookings(id,court_id,customer_id,guest_phone,date,status,total_price,booking_code,branch_id,branch_name,branch_address,branch_phone,court_name,hold_token_hash,created_at)
          OVERRIDING SYSTEM VALUE VALUES (?,1,?,?,?::date,?,100000,?,7,'Thu Duc','123 Street','0901234567','Court 1',?,?::timestamptz)
          """,i,i==4?null:i==3?20L:10L,i==4?"0901234567":null,i==2?"2020-01-01":"2099-12-01",i==2?"COMPLETED":"PENDING_PAYMENT","BK0000000"+i,BookingQueries.hash("guest-secret"),"2026-11-01T00:00:0"+i+"Z");
        for(int i=1;i<=4;i++) jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (?,?,?::date,'18:00','18:30',100000)",i,i,i==2?"2020-01-01":"2099-12-01");
    }
    @Test void historyRequiresVerifiedIdentityAndDoesNotTrustHeaders() throws Exception {
        mvc.perform(get("/api/bookings/my").header("X-User-Id","10")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/my").header("Authorization","Bearer broken")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/my").with(jwt().jwt(j->j.subject("abc")))).andExpect(status().isUnauthorized());
    }
    @Test void ownerHistoryIsPaginatedAndStable() throws Exception {
        mvc.perform(get("/api/bookings/my?size=1").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(2));
        mvc.perform(get("/api/bookings/my?size=1&page=1").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(jsonPath("$.items[0].id").value(1));
    }
    @Test void tabsAndBounds() throws Exception {
        for(var pair:Map.of("UPCOMING",1,"COMPLETED",2).entrySet()) mvc.perform(get("/api/bookings/my?status="+pair.getKey()).with(jwt().jwt(j->j.subject("10"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(pair.getValue()));
        mvc.perform(get("/api/bookings/my?status=CANCELLED").with(jwt().jwt(j->j.subject("10")))).andExpect(jsonPath("$.total").value(0));
        for(String query:List.of("size=101","size=0","page=-1","page=2147483647","status=OTHER"))
            mvc.perform(get("/api/bookings/my?"+query).with(jwt().jwt(j->j.subject("10")))).andExpect(status().isBadRequest());
    }
    @Test void detailsEnforceOwnershipAndRedactSecrets() throws Exception {
        mvc.perform(get("/api/bookings/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/1").with(jwt().jwt(j->j.subject("20")))).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/1").header("X-Hold-Token","guest-secret")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/1").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.branchAddress").value("123 Street"))
            .andExpect(jsonPath("$.slots[0].startTime").value("18:00:00"))
            .andExpect(jsonPath("$.holdTokenHash").doesNotExist()).andExpect(jsonPath("$.customerId").doesNotExist());
        mvc.perform(get("/api/bookings/4").header("X-Hold-Token","guest-secret")).andExpect(status().isOk());
        mvc.perform(get("/api/bookings/4").header("X-Hold-Token","wrong")).andExpect(status().isNotFound());
    }
    @Test void historyLoadsSlotsInOrderAndSlotLookupByBookingIsIndexed() throws Exception {
        jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES (1,1,'2099-12-01','17:00','17:30',90000)");
        mvc.perform(get("/api/bookings/my?size=1&page=1").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(jsonPath("$.items[0].id").value(1)).andExpect(jsonPath("$.items[0].slots.length()").value(2))
            .andExpect(jsonPath("$.items[0].slots[0].startTime").value("17:00:00")).andExpect(jsonPath("$.items[0].slots[1].startTime").value("18:00:00"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='booking_slots' AND indexdef LIKE '%(booking_id%'",Integer.class)).isPositive();
    }
    String event(UUID id,String type,String amount) throws Exception {
        return json.writeValueAsString(Map.of("eventId",id,"type",type,"payment",Map.of("id",42,"bookingId",1,"branchId",7,"amount",Integer.parseInt(amount),"status","CONFIRMED","checkInCode","12345678901234567890123456789012","qrImageUrl","/api/payments/1/ticket/qr","receiptImageUrl","https://res.cloudinary.com/demo/bill.png")));
    }
    void consume(String e) { tx.executeWithoutResult(s->{try{new BookingPaymentEvents(jdbc,json).accept(e);}catch(Exception x){throw new RuntimeException(x);}}); }
    @Test void paymentEventsPersistTicketDeduplicateAndNeverRegress() throws Exception {
        String confirmed=event(UUID.randomUUID(),"PaymentConfirmedEvent","100000"); consume(confirmed);consume(confirmed);
        consume(event(UUID.randomUUID(),"PaymentSubmittedEvent","100000"));
        consume(event(UUID.randomUUID(),"BookingCancelledEvent","100000"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_inbox",Integer.class)).isEqualTo(3);
        mvc.perform(get("/api/bookings/1").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(jsonPath("$.checkInCode").value("12345678901234567890123456789012"))
            .andExpect(jsonPath("$.receiptImageUrl").value("https://res.cloudinary.com/demo/bill.png"));
    }
    @Test void mismatchedEventsRollbackInboxAndCancelledBookingCannotResurrect() throws Exception {
        assertThatThrownBy(()->consume(eventUnchecked("90000"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_inbox",Integer.class)).isZero();
        consume(event(UUID.randomUUID(),"PaymentRejectedEvent","100000"));
        consume(event(UUID.randomUUID(),"PaymentConfirmedEvent","100000"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT checkin_code FROM bookings WHERE id=1",String.class)).isNull();
    }
    String eventUnchecked(String amount) { try{return event(UUID.randomUUID(),"PaymentConfirmedEvent",amount);}catch(Exception e){throw new RuntimeException(e);} }
}
