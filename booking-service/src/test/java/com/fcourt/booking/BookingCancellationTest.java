package com.fcourt.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class BookingCancellationTest extends BookingApiTest {
    Jwt user(long id){return Jwt.withTokenValue("test").header("alg","RS256").subject(""+id).build();}
    BookingCancellation at(Instant now){return new BookingCancellation(jdbc,new BookingQueries(jdbc,Clock.fixed(now,ZoneOffset.UTC)),json,Clock.fixed(now,ZoneOffset.UTC),12,BigDecimal.valueOf(100));}
    Instant cutoff(){return Instant.parse("2099-11-30T23:00:00Z");}
    private static final String PAID="UPDATE bookings SET status='CONFIRMED',payment_status='CONFIRMED',payment_id=42 WHERE id=1";
    @Test void unpaidBookingInsideCutoffCanBeCancelledWithoutRefund(){
        var late=cutoff().plusSeconds(3600);
        var q=at(late).quote(1,user(10));
        assertThat(q.allowed()).isTrue();assertThat(q.refundAmount()).isEqualByComparingTo("0");
        tx.execute(s->at(late).cancel(1,"Đổi ý",user(10)));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=1",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM bookings WHERE id=1",BigDecimal.class)).isEqualByComparingTo("0");
        var event=jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class);
        assertThat(event).doesNotContain("\"refundAmount\": 100000");
    }
    @Test void paidBookingInsideCutoffIsRejected(){
        jdbc.update(PAID);
        assertThat(at(cutoff().plusNanos(1)).quote(1,user(10)).allowed()).isFalse();
        assertThatThrownBy(()->tx.execute(s->at(cutoff().plusNanos(1)).cancel(1,"Đổi lịch",user(10)))).hasMessageContaining("400");
    }
    @Test void paidBookingOutsideCutoffRefunds(){
        jdbc.update(PAID);
        var q=at(cutoff()).quote(1,user(10));
        assertThat(q.allowed()).isTrue();assertThat(q.refundAmount()).isEqualByComparingTo("100000");
    }
    @Test void awaitingConfirmationWithReceiptKeepsCutoff(){
        jdbc.update("UPDATE bookings SET status='AWAITING_CONFIRMATION',receipt_image_url='http://r' WHERE id=1");
        assertThat(at(cutoff().plusNanos(1)).quote(1,user(10)).allowed()).isFalse();
    }
    @Test void exactCutoffAllowedButOneNanosecondLaterRejected(){
        jdbc.update(PAID);
        assertThat(at(cutoff()).quote(1,user(10)).allowed()).isTrue();
        assertThat(at(cutoff().plusNanos(1)).quote(1,user(10)).allowed()).isFalse();
        assertThatThrownBy(()->tx.execute(s->at(cutoff().plusNanos(1)).cancel(1,"Đổi lịch",user(10)))).hasMessageContaining("400");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Integer.class)).isZero();
    }
    @Test void cancellationApiIsOwnerOnlyAndRequiresReason()throws Exception{
        mvc.perform(post("/api/bookings/1/cancel").contentType("application/json").content("{\"reason\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bookings/1/cancel").with(jwt().jwt(j->j.subject("20"))).contentType("application/json").content("{\"reason\":\"test\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/1/cancel").with(jwt().jwt(j->j.subject("10"))).contentType("application/json").content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings/1/cancellation-policy").with(jwt().jwt(j->j.subject("10"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.refundAmount").value(0));
        mvc.perform(post("/api/bookings/2/cancel").with(jwt().jwt(j->j.subject("10"))).contentType("application/json").content("{\"reason\":\"test\"}")).andExpect(status().isBadRequest());
    }
    @Test void paidCancellationPreservesFinancialStateAndSlotHistory()throws Exception{
        jdbc.update("UPDATE bookings SET status='CONFIRMED',payment_status='CONFIRMED',payment_id=42 WHERE id=1");
        mvc.perform(post("/api/bookings/1/cancel").with(jwt().jwt(j->j.subject("10"))).contentType("application/json").content("{\"reason\":\" Đổi lịch \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundAmount").value(100000)).andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.cancellationReason").value("Đổi lịch")).andExpect(jsonPath("$.slots.length()").value(1));
        var event=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class));
        assertThat(event.path("booking").path("refundAmount").asInt()).isEqualTo(100000);
        assertThat(event.path("booking").path("cancelledByRole").asText()).isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=1",Boolean.class)).isFalse();
        // Same court/date/time can be booked again while the cancelled booking retains its history.
        jdbc.update("INSERT INTO booking_slots(booking_id,court_id,booking_date,slot_start,slot_end,price_at_booking) VALUES(3,1,'2099-12-01','18:00','18:30',100000)");
        consume(event(UUID.randomUUID(),"PaymentSubmittedEvent","100000"));
        assertThat(jdbc.queryForObject("SELECT payment_status FROM bookings WHERE id=1",String.class)).isEqualTo("CONFIRMED");
    }
    @Test void duplicateConcurrentCancellationCreatesOneOutboxEvent()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var calls=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++)calls.add(pool.submit(()->{try{start.await();tx.execute(s->at(cutoff()).cancel(1,"Đổi lịch",user(10)));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            start.countDown();for(var result:calls)result.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CANCELLED");
    }
    @Test void cancelledEventCarriesIsoSlotDatesAndTimesForConsumers()throws Exception{
        tx.execute(s->at(cutoff()).cancel(1,"Đổi lịch",user(10)));
        var event=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class));
        var slot=event.path("booking").path("slots").get(0);
        assertThat(slot.path("date").asText()).isEqualTo("2099-12-01");
        assertThat(slot.path("startTime").asText()).isEqualTo("18:00:00");
        assertThat(slot.path("courtId").asLong()).isEqualTo(1);
        assertThat(event.path("booking").path("branchId").asLong()).isEqualTo(7);
    }
    @Test void outboxFailureRollsBackBookingAndReservation(){
        jdbc.execute("ALTER TABLE booking_outbox ADD CONSTRAINT test_failure CHECK(false) NOT VALID");
        try{
            assertThatThrownBy(()->tx.execute(s->at(cutoff()).cancel(1,"Đổi lịch",user(10)))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("PENDING_PAYMENT");
            assertThat(jdbc.queryForObject("SELECT active FROM booking_slots WHERE booking_id=1",Boolean.class)).isTrue();
        }finally{jdbc.execute("ALTER TABLE booking_outbox DROP CONSTRAINT test_failure");}
    }
}
