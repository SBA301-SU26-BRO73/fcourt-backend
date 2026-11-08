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
@SpringBootTest(properties={"booking.events-enabled=false","booking.integration-token=integration"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
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
    @Autowired BookingVoucherBridge voucherBridge;
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE booking_outbox,booking_inbox,bookings CASCADE");
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

    @Test void completedBookingDetailExposesTheBookedCourtForReviewVerification() throws Exception {
        jdbc.update("UPDATE bookings SET court_id=7,status='COMPLETED' WHERE id=1");
        mvc.perform(get("/api/bookings/1").with(jwt().jwt(token->token.subject("10"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.courtId").value(7)).andExpect(jsonPath("$.branchId").value(7))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.customerId").doesNotExist());
        mvc.perform(get("/api/bookings/1").with(jwt().jwt(token->token.subject("20")))).andExpect(status().isNotFound());
    }

    @Test void voucherAttachmentRequiresMachineAndCustomerIdentityAndFreezeProtectsPrice() throws Exception {
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE id=1",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        UUID application=UUID.randomUUID();
        String body="{\"applicationId\":\""+application+"\",\"voucherId\":7,\"originalAmount\":100000,\"discountAmount\":20000,\"finalAmount\":80000}";
        mvc.perform(post("/api/internal/bookings/1/voucher").with(jwt().jwt(j->j.subject("10"))).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/bookings/1/voucher").with(jwt().jwt(j->j.subject("20"))).header("X-Service-Token","integration")
                .contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/internal/bookings/1/voucher").with(jwt().jwt(j->j.subject("10"))).header("X-Service-Token","integration")
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.totalPrice").value(80000));
        mvc.perform(post("/api/internal/bookings/1/payment-freeze").with(jwt().jwt(j->j.subject("10"))).header("X-Service-Token","integration")
                .contentType("application/json").content("{\"amount\":100000}")).andExpect(status().isConflict());
        mvc.perform(post("/api/internal/bookings/1/payment-freeze").with(jwt().jwt(j->j.subject("10"))).header("X-Service-Token","integration")
                .contentType("application/json").content("{\"amount\":80000}")).andExpect(status().isOk()).andExpect(jsonPath("$.paymentFrozen").value(true));
        mvc.perform(post("/api/internal/bookings/1/voucher").with(jwt().jwt(j->j.subject("10"))).header("X-Service-Token","integration")
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/internal/bookings/1/voucher").with(jwt().jwt(j->j.subject("10"))).header("X-Service-Token","integration")
                .contentType("application/json").content(body.replace(application.toString(),UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/internal/bookings/1/voucher-snapshot")).andExpect(status().isForbidden());
        mvc.perform(get("/api/internal/bookings/1/voucher-snapshot").header("X-Service-Token","integration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(10));
    }

    @Test void voucherAppliedOutboxIsAtomicWithPaidBookingAndNeverDuplicated() throws Exception {
        UUID application=UUID.randomUUID();
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true WHERE id=1",application);
        consume(event(UUID.randomUUID(),"PaymentSubmittedEvent","80000"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Long.class)).isZero();
        String paid=event(UUID.randomUUID(),"PaymentConfirmedEvent","80000");consume(paid);consume(paid);
        consume(event(UUID.randomUUID(),"PaymentConfirmedEvent","80000"));
        var out=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class));
        assertThat(out.path("type").asText()).isEqualTo("VoucherAppliedEvent");
        assertThat(out.path("voucher").path("applicationId").asText()).isEqualTo(application.toString());
        assertThat(out.path("voucher").path("customerId").asLong()).isEqualTo(10);
        assertThat(out.path("voucher").path("paymentId").asLong()).isEqualTo(42);
        assertThat(out.path("voucher").path("finalAmount").decimalValue()).isEqualByComparingTo("80000");
        assertThat(out.path("voucher").has("customerName")).isFalse();
        assertThat(out.path("voucher").has("customerPhone")).isFalse();
        assertThat(out.path("voucher").path("bookingCode").asText()).isEqualTo("BK00000001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Long.class)).isEqualTo(1);
    }

    @Test void voucherAppliedEventCarriesCustomerDisplaySnapshotWhenBookingHasIt() throws Exception {
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true,customer_name=?,guest_phone=? WHERE id=1",
                UUID.randomUUID(),"  Nguyen Van A ","0912345678");
        consume(event(UUID.randomUUID(),"PaymentConfirmedEvent","80000"));
        var voucher=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class)).path("voucher");
        assertThat(voucher.path("customerName").asText()).isEqualTo("Nguyen Van A");
        assertThat(voucher.path("customerPhone").asText()).isEqualTo("0912345678");
        assertThat(voucher.path("bookingCode").asText()).isEqualTo("BK00000001");
    }

    @Test void snapshotBackfillRequiresServiceTokenAndEmitsOnlyPaidVoucherBookingsWithBoundedCursor() throws Exception {
        for(int id:new int[]{1,3}) jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true,payment_status='CONFIRMED',customer_name=?,guest_phone=? WHERE id=?",
                UUID.randomUUID(),"Name "+id,"09123456"+id+"0",id);
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true,customer_name='Unpaid' WHERE id=2",UUID.randomUUID());
        mvc.perform(post("/api/internal/bookings/voucher-snapshot-backfill")).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/bookings/voucher-snapshot-backfill").header("X-Service-Token","wrong")).andExpect(status().isForbidden());
        for(String bad:List.of("limit=0","limit=101","afterId=-1")) mvc.perform(post("/api/internal/bookings/voucher-snapshot-backfill?"+bad).header("X-Service-Token","integration")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Long.class)).isZero();
        mvc.perform(post("/api/internal/bookings/voucher-snapshot-backfill?limit=1").header("X-Service-Token","integration"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.emitted").value(1))
                .andExpect(jsonPath("$.lastBookingId").value(1)).andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(post("/api/internal/bookings/voucher-snapshot-backfill?limit=1&afterId=1").header("X-Service-Token","integration"))
                .andExpect(jsonPath("$.emitted").value(1)).andExpect(jsonPath("$.lastBookingId").value(3)).andExpect(jsonPath("$.hasMore").value(false));
        var events=jdbc.queryForList("SELECT payload::text FROM booking_outbox ORDER BY id",String.class);
        assertThat(events).hasSize(2);
        var first=json.readTree(events.get(0));
        assertThat(first.path("type").asText()).isEqualTo("VoucherSnapshotBackfillEvent");
        assertThat(first.path("voucher").path("bookingId").asLong()).isEqualTo(1);
        assertThat(first.path("voucher").path("customerId").asLong()).isEqualTo(10);
        assertThat(first.path("voucher").path("customerName").asText()).isEqualTo("Name 1");
        assertThat(first.path("voucher").path("bookingCode").asText()).isEqualTo("BK00000001");
    }

    @Test void latePaymentAfterCancellationKeepsBookingCancelledButRecordsVoucherUse() throws Exception {
        UUID application=UUID.randomUUID();
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true,status='CANCELLED' WHERE id=1",application);
        consume(event(UUID.randomUUID(),"PaymentConfirmedEvent","80000"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM bookings WHERE id=1",String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Long.class)).isEqualTo(1);
        var out=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class));
        assertThat(out.path("type").asText()).isEqualTo("VoucherAppliedEvent");
        assertThat(out.path("voucher").path("applicationId").asText()).isEqualTo(application.toString());
    }

    @Test void concurrentApplyAndFreezeCannotAcceptTwoDifferentPrices() throws Exception {
        jdbc.update("UPDATE bookings SET hold_expires_at=? WHERE id=1",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        var actor=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").header("alg","RS256").subject("10").build();
        var attachment=new BookingVoucherBridge.Attachment(UUID.randomUUID(),7,new java.math.BigDecimal("100000"),new java.math.BigDecimal("20000"),new java.math.BigDecimal("80000"));
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var apply=executor.submit(()->{start.await();try{voucherBridge.attach(1,actor,attachment);return true;}catch(org.springframework.web.server.ResponseStatusException error){return false;}});
            var freeze=executor.submit(()->{start.await();try{voucherBridge.freeze(1,actor,null,new java.math.BigDecimal("100000"));return true;}catch(org.springframework.web.server.ResponseStatusException error){return false;}});
            start.countDown();
            assertThat(List.of(apply.get(10,java.util.concurrent.TimeUnit.SECONDS),freeze.get(10,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        var order=jdbc.queryForMap("SELECT payment_frozen,total_price FROM bookings WHERE id=1");
        assertThat((java.math.BigDecimal)order.get("total_price")).isEqualByComparingTo(Boolean.TRUE.equals(order.get("payment_frozen"))?"100000":"80000");
    }

    @Test void databaseRejectsPartialVoucherSnapshotsAndNegativeFinalTotals() {
        assertThatThrownBy(()->jdbc.update("UPDATE bookings SET voucher_application_id=? WHERE id=1",UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=100001,total_price=-1 WHERE id=1",UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT total_price FROM bookings WHERE id=1",java.math.BigDecimal.class)).isEqualByComparingTo("100000");
    }

    @Test void voucherUsageOutboxPreservesOriginalPaymentTime() throws Exception {
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true WHERE id=1",UUID.randomUUID());
        var paid=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(event(UUID.randomUUID(),"PaymentConfirmedEvent","80000"));
        String time="2026-01-02T03:04:05.123456Z";
        ((com.fasterxml.jackson.databind.node.ObjectNode)paid.path("payment")).put("confirmedAt",time);consume(paid.toString());
        var published=json.readTree(jdbc.queryForObject("SELECT payload::text FROM booking_outbox",String.class));
        assertThat(published.path("voucher").path("usedAt").asText()).isEqualTo(time);
    }

    @Test void malformedPaymentTimestampRollsBackBookingAndVoucherOutbox() throws Exception {
        jdbc.update("UPDATE bookings SET voucher_application_id=?,voucher_id=7,original_amount=100000,discount_amount=20000,total_price=80000,payment_frozen=true WHERE id=1",UUID.randomUUID());
        var paid=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(event(UUID.randomUUID(),"PaymentConfirmedEvent","80000"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)paid.path("payment")).put("confirmedAt","invalid-date");
        assertThatThrownBy(()->consume(paid.toString())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=1",String.class)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_inbox",Long.class)).isZero();
    }
}
