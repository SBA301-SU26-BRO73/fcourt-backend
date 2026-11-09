package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(properties={"voucher.grpc.enabled=false", "voucher.reservation-expiry-ms=3600000"})
@EmbeddedKafka(partitions=1, topics={"fcourt.booking.events","fcourt.payment.events"}, bootstrapServersProperty="spring.kafka.bootstrap-servers")
@AutoConfigureMockMvc
@DirtiesContext
class VoucherApplyTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Map<Long,ObjectNode> ORDERS = new ConcurrentHashMap<>();
    static final AtomicInteger ATTACH_STATUS = new AtomicInteger(200);
    static final AtomicInteger CALLS = new AtomicInteger();
    static final HttpServer BOOKING = startBooking();
    static final Instant NOW = Instant.parse("2030-01-10T00:00:00Z");

    static HttpServer startBooking() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/", exchange -> {
                CALLS.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                boolean internal = path.startsWith("/api/internal/");
                long id = Long.parseLong(path.split("/")[internal ? 4 : 3]);
                var order = ORDERS.get(id);
                int status = order == null ? 404 : 200;
                if (internal && !"integration".equals(exchange.getRequestHeaders().getFirst("X-Service-Token"))) status=403;
                if (order != null && (!internal || path.endsWith("/voucher"))
                        && !("Bearer customer-"+order.path("customerId").asLong()).equals(exchange.getRequestHeaders().getFirst("Authorization"))) status=404;
                if (status==200 && exchange.getRequestMethod().equals("DELETE")) {
                    order.putNull("voucherApplicationId");order.putNull("voucherId");
                    order.set("totalPrice",order.path("originalAmount"));
                    order.putNull("originalAmount");order.putNull("discountAmount");
                }
                if (status==200 && exchange.getRequestMethod().equals("POST")) {
                    var body=JSON.readTree(exchange.getRequestBody());
                    order.put("voucherApplicationId",body.path("applicationId").asText());
                    order.set("voucherId",body.path("voucherId"));
                    order.set("originalAmount",body.path("originalAmount"));
                    order.set("discountAmount",body.path("discountAmount"));
                    order.set("totalPrice",body.path("finalAmount"));
                    status=ATTACH_STATUS.get();
                }
                byte[] bytes=(order==null ? "{}" : order.toString()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);
                try(var output=exchange.getResponseBody()){output.write(bytes);}
            });
            server.start();return server;
        } catch(java.io.IOException error){throw new ExceptionInInitializerError(error);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);
        registry.add("spring.datasource.password",DB::getPassword);
        registry.add("voucher.booking-url",()->"http://127.0.0.1:"+BOOKING.getAddress().getPort());
        registry.add("voucher.booking-integration-token",()->"integration");
    }
    @TestConfiguration static class Time {
        @Bean @Primary Clock testClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
    }
    @AfterAll static void stop(){BOOKING.stop(0);}
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired VoucherApplyService service;
    @Autowired VoucherUsageEvents events;
    @Autowired VoucherReservationExpiry expiry;
    @Autowired VoucherRedemptions applications;
    @Autowired KafkaTemplate<String,String> kafka;

    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE vouchers,voucher_inbox RESTART IDENTITY CASCADE");
        ORDERS.clear();ATTACH_STATUS.set(200);CALLS.set(0);
        order(1,10,7);order(2,20,7);order(3,10,7);order(4,10,8);
    }
    void order(long id,long customer,long branch){
        ORDERS.put(id,JSON.createObjectNode().put("id",id).put("customerId",customer).put("branchId",branch)
                .put("status","PENDING_PAYMENT").put("totalPrice",100000).put("holdExpiresAt",NOW.plusSeconds(3600).toString()).put("paymentFrozen",false));
    }
    Jwt customer(long id){return Jwt.withTokenValue("customer-"+id).header("alg","RS256").subject(Long.toString(id)).claim("roles",List.of("CUSTOMER")).build();}
    long voucher(String code,int limit){
        return jdbc.queryForObject("""
                INSERT INTO vouchers(code,branch_id,discount_type,discount_value,max_discount_amount,min_order_amount,start_date,end_date,usage_limit,status)
                VALUES (?,7,'PERCENTAGE',20,30000,100000,'2030-01-01T00:00:00Z','2030-02-01T00:00:00Z',?,'ACTIVE') RETURNING id
                """,Long.class,code,limit);
    }
    VoucherRedemption apply(long booking,long customer){return service.apply(new VoucherApplyRequest("SAVE",booking,new java.math.BigDecimal("100000")),customer(customer));}
    void rejects(long booking,String message) throws Exception {
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(customer(10))).contentType("application/json")
                .content("{\"code\":\"SAVE\",\"bookingId\":"+booking+",\"totalAmount\":100000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(message)));
    }
    ObjectNode applied(VoucherRedemption application,UUID eventId){
        var event=JSON.createObjectNode().put("eventId",eventId.toString()).put("type","VoucherAppliedEvent");
        event.putObject("voucher").put("applicationId",application.applicationId().toString()).put("voucherId",application.voucherId())
                .put("bookingId",application.bookingId()).put("customerId",application.customerId()).put("branchId",application.branchId()).put("paymentId",99)
                .put("originalAmount",application.originalAmount()).put("discountAmount",application.discountAmount()).put("finalAmount",application.finalAmount());
        return event;
    }

    @Test void applyUsesTrustedAmountsAndRetryDoesNotDoubleDiscount() throws Exception {
        voucher("SAVE",10);
        var first=apply(1,10);var retry=apply(1,10);
        assertThat(retry.applicationId()).isEqualTo(first.applicationId());
        assertThat(first.discountAmount()).isEqualByComparingTo("20000");
        assertThat(first.finalAmount()).isEqualByComparingTo("80000");
        assertThat(ORDERS.get(1L).path("totalPrice").decimalValue()).isEqualByComparingTo("80000");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions",Long.class)).isEqualTo(1);
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(customer(10))).contentType("application/json")
                .content("{\"code\":\" save \",\"bookingId\":1,\"totalAmount\":100000}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.customerId").doesNotExist()).andExpect(jsonPath("$.finalAmount").value(80000));
    }

    String validateBody(long booking){return "{\"code\":\" save \",\"bookingId\":"+booking+"}";}

    @Test void validateQuotesDiscountWithoutWritingAnything() throws Exception {
        voucher("SAVE",10);
        mvc.perform(post("/api/vouchers/validate").with(jwt().jwt(customer(10))).contentType("application/json").content(validateBody(1)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.reason").value("VALID"))
                .andExpect(jsonPath("$.originalAmount").value(100000)).andExpect(jsonPath("$.discountAmount").value(20000))
                .andExpect(jsonPath("$.finalAmount").value(80000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
        assertThat(ORDERS.get(1L).path("totalPrice").asInt()).isEqualTo(100000);
        mvc.perform(post("/api/vouchers/validate").with(jwt().jwt(customer(10))).contentType("application/json").content(validateBody(4)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("WRONG_BRANCH"))
                .andExpect(jsonPath("$.finalAmount").value(100000));
        mvc.perform(post("/api/vouchers/validate").with(jwt().jwt(customer(10))).contentType("application/json")
                .content("{\"code\":\"NOPE\",\"bookingId\":1}")).andExpect(jsonPath("$.reason").value("NOT_FOUND"));
        mvc.perform(post("/api/vouchers/validate").with(jwt().jwt(customer(20))).contentType("application/json").content(validateBody(1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/vouchers/validate").contentType("application/json").content(validateBody(1))).andExpect(status().isUnauthorized());
    }

    @Test void removeRestoresBookingReleasesReservationAndIsIdempotent() throws Exception {
        voucher("SAVE",2);apply(1,10);
        assertThat(ORDERS.get(1L).path("totalPrice").asInt()).isEqualTo(80000);
        mvc.perform(delete("/api/vouchers/apply/1").with(jwt().jwt(customer(20)))).andExpect(status().isNotFound());
        assertThat(applications.booking(1,false)).isPresent();
        mvc.perform(delete("/api/vouchers/apply/1").with(jwt().jwt(customer(10)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true)).andExpect(jsonPath("$.totalAmount").value(100000));
        assertThat(ORDERS.get(1L).path("totalPrice").asInt()).isEqualTo(100000);
        assertThat(applications.booking(1,false)).isEmpty();
        mvc.perform(delete("/api/vouchers/apply/1").with(jwt().jwt(customer(10)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(false)).andExpect(jsonPath("$.totalAmount").value(100000));
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
        assertThat(apply(2,20).status()).isEqualTo("RESERVED");
        assertThat(apply(1,10).discountAmount()).isEqualByComparingTo("20000");
    }

    @Test void removeIsRefusedWhenPaidOrPaymentFrozen() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        ORDERS.get(1L).put("paymentFrozen",true);
        mvc.perform(delete("/api/vouchers/apply/1").with(jwt().jwt(customer(10)))).andExpect(status().isConflict());
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RESERVED");
        ORDERS.get(1L).put("paymentFrozen",false);
        events.accept(applied(application,UUID.randomUUID()).toString());
        mvc.perform(delete("/api/vouchers/apply/1").with(jwt().jwt(customer(10)))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void sixValidationStepsReturnVietnameseReasons() throws Exception {
        rejects(1,"không tồn tại");
        long id=voucher("SAVE",10);
        rejects(4,"chi nhánh");
        jdbc.update("UPDATE vouchers SET end_date='2030-01-09' WHERE id=?",id);rejects(1,"hết hạn");
        jdbc.update("UPDATE vouchers SET end_date='2030-02-01',start_date='2030-01-11' WHERE id=?",id);rejects(1,"chưa đến");
        jdbc.update("UPDATE vouchers SET start_date='2030-01-01',used_count=usage_limit WHERE id=?",id);rejects(1,"hết lượt");
        jdbc.update("UPDATE vouchers SET used_count=0,min_order_amount=100001 WHERE id=?",id);rejects(1,"tối thiểu");
        jdbc.update("UPDATE vouchers SET min_order_amount=100000 WHERE id=?",id);
        var application=apply(1,10);events.accept(applied(application,UUID.randomUUID()).toString());
        rejects(3,"đã sử dụng");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers WHERE id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void untrustedIdentityAmountsAndExpiredBookingsAreRejected() throws Exception {
        voucher("SAVE",10);
        String body="{\"code\":\"SAVE\",\"bookingId\":1,\"totalAmount\":100000}";
        mvc.perform(post("/api/vouchers/apply").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(token->token.subject("10").claim("roles",List.of("STAFF"))))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertThat(CALLS.get()).isZero();
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(customer(20))).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(customer(10))).contentType("application/json").content(body.replace("100000","1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("không khớp")));
        ORDERS.get(1L).put("holdExpiresAt",NOW.toString());
        mvc.perform(post("/api/vouchers/apply").with(jwt().jwt(customer(10))).contentType("application/json").content(body))
                .andExpect(status().isGone());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions",Long.class)).isZero();
    }

    @Test void twoCustomersCannotReserveTheLastUsageConcurrently() throws Exception {
        voucher("SAVE",1);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->{start.await();try{apply(1,10);return true;}catch(org.springframework.web.server.ResponseStatusException error){return false;}});
            var two=executor.submit(()->{start.await();try{apply(2,20);return true;}catch(org.springframework.web.server.ResponseStatusException error){return false;}});
            start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions WHERE status='RESERVED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
    }

    @Test void paymentEventReplayCountsOnceAndRejectsMismatchedMoney() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        var bad=applied(application,UUID.randomUUID());((ObjectNode)bad.path("voucher")).put("finalAmount",1);
        assertThatThrownBy(()->events.accept(bad.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_inbox",Long.class)).isZero();
        var valid=applied(application,UUID.randomUUID());events.accept(valid.toString());events.accept(valid.toString());
        events.accept(applied(application,UUID.randomUUID()).toString());
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("APPLIED");
        var conflict=applied(application,UUID.randomUUID());((ObjectNode)conflict.path("voucher")).put("paymentId",100);
        assertThatThrownBy(()->events.accept(conflict.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void actualKafkaDeliveryIncrementsUsageOnlyOnce() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);var first=applied(application,UUID.randomUUID());
        kafka.send("fcourt.booking.events","1",first.toString()).get(10,TimeUnit.SECONDS);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->
                assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1));
        kafka.send("fcourt.booking.events","1",first.toString()).get(10,TimeUnit.SECONDS);
        kafka.send("fcourt.booking.events","1",applied(application,UUID.randomUUID()).toString()).get(10,TimeUnit.SECONDS);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_inbox",Long.class)).isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_usages",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_at_source FROM voucher_usages",String.class)).isEqualTo("RECORDED_AT");
    }

    @Test void uncertainAttachmentIsRetainedForSafeRetryAndDiscountNeverGoesNegative() throws Exception {
        voucher("SAVE",10);jdbc.update("UPDATE vouchers SET discount_type='FIXED_AMOUNT',discount_value=200000,max_discount_amount=NULL,min_order_amount=0");
        ATTACH_STATUS.set(503);
        assertThatThrownBy(()->apply(1,10)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                error->assertThat(error.getStatusCode().value()).isEqualTo(503));
        var reserved=applications.booking(1,false).orElseThrow();assertThat(reserved.status()).isEqualTo("RESERVED");
        ATTACH_STATUS.set(200);var retry=apply(1,10);
        assertThat(retry.applicationId()).isEqualTo(reserved.applicationId());
        assertThat(retry.discountAmount()).isEqualByComparingTo("100000");assertThat(retry.finalAmount()).isZero();
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
    }

    @Test void expiryCannotReleaseFrozenPaymentsButCancellationReleasesUnpaidQuota() throws Exception {
        voucher("SAVE",10);var first=apply(1,10);
        ORDERS.get(1L).put("holdExpiresAt",NOW.minusSeconds(1).toString()).put("paymentFrozen",true);
        expiry.reconcile(first);assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RESERVED");
        ORDERS.get(1L).put("paymentFrozen",false);expiry.reconcile(first);
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RELEASED");
        var second=apply(2,20);
        var cancel=JSON.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("type","PaymentRejectedEvent");
        cancel.putObject("payment").put("id",99).put("bookingId",2).put("branchId",7).put("amount",second.finalAmount());
        events.accept(cancel.toString());assertThat(applications.booking(2,false).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isZero();
        var paid=apply(3,10);events.accept(applied(paid,UUID.randomUUID()).toString());
        ((ObjectNode)cancel.path("payment")).put("bookingId",3);cancel.put("eventId",UUID.randomUUID().toString());events.accept(cancel.toString());
        assertThat(applications.booking(3,false).orElseThrow().status()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void cancelledFrozenBookingWaitsForPaidEventRatherThanLosingUsage() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        ORDERS.get(1L).put("holdExpiresAt",NOW.minusSeconds(1).toString()).put("paymentFrozen",true).put("status","CANCELLED");
        expiry.reconcile(application);
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RESERVED");
        events.accept(applied(application,UUID.randomUUID()).toString());
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void bookingCancellationDoesNotReleaseFrozenVoucherBeforePaymentDecision() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        ORDERS.get(1L).put("status","CANCELLED").put("paymentFrozen",true);
        var cancelled=JSON.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("type","BookingCancelledEvent");
        cancelled.putObject("booking").put("id",1).put("bookingId",1).put("branchId",7).put("customerId",10)
                .put("paymentId",99).put("paymentStatus","PENDING");
        events.accept(cancelled.toString());
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RESERVED");
        events.accept(applied(application,UUID.randomUUID()).toString());
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void paidUsagePreservesOriginalTimestampAndReplayCannotRewriteIt() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        var paymentTime=Instant.parse("2030-01-09T23:00:00.123456Z");
        var event=applied(application,UUID.randomUUID());((ObjectNode)event.path("voucher")).put("usedAt",paymentTime.toString());
        events.accept(event.toString());events.accept(event.toString());
        var replay=applied(application,UUID.randomUUID());((ObjectNode)replay.path("voucher")).put("usedAt",NOW.toString());events.accept(replay.toString());
        var usage=jdbc.queryForMap("SELECT * FROM voucher_usages");
        assertThat(usage.get("user_id")).isEqualTo(10L);assertThat(usage.get("booking_id")).isEqualTo(1L);
        assertThat((java.math.BigDecimal)usage.get("discount_amount")).isEqualByComparingTo("20000");
        assertThat(((java.sql.Timestamp)usage.get("used_at")).toInstant()).isEqualTo(paymentTime);
        assertThat(usage.get("used_at_source")).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_usages",Long.class)).isEqualTo(1);
    }

    ObjectNode backfill(VoucherRedemption application){
        var event=JSON.createObjectNode().put("eventId",UUID.randomUUID().toString()).put("type","VoucherSnapshotBackfillEvent");
        event.putObject("voucher").put("applicationId",application.applicationId().toString()).put("voucherId",application.voucherId())
                .put("bookingId",application.bookingId()).put("customerId",application.customerId()).put("branchId",application.branchId())
                .put("bookingCode","FC00000001").put("customerName","Nguyen Van A").put("customerPhone","0912345678");
        return event;
    }

    @Test void backfillFillsOnlyMissingSnapshotColumnsAndIsIdempotent() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        events.accept(applied(application,UUID.randomUUID()).toString());
        assertThat(jdbc.queryForObject("SELECT customer_name FROM voucher_usages",String.class)).isNull();
        events.accept(backfill(application).toString());events.accept(backfill(application).toString());
        var usage=jdbc.queryForMap("SELECT * FROM voucher_usages");
        assertThat(usage.get("customer_name")).isEqualTo("Nguyen Van A");assertThat(usage.get("customer_phone_digits")).isEqualTo("0912345678");
        assertThat(usage.get("booking_code")).isEqualTo("FC00000001");
        var other=backfill(application);((ObjectNode)other.path("voucher")).put("customerName","Someone Else").put("customerPhone","0999999999");
        events.accept(other.toString());
        assertThat(jdbc.queryForObject("SELECT customer_name FROM voucher_usages",String.class)).isEqualTo("Nguyen Van A");
        assertThat(jdbc.queryForObject("SELECT customer_phone_digits FROM voucher_usages",String.class)).isEqualTo("0912345678");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(1);
    }

    @Test void backfillIgnoresMismatchedIdentityMalformedEventsAndMissingUsage() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        events.accept(backfill(application).toString()); // usage not recorded yet: nothing to fill, no failure
        events.accept(applied(application,UUID.randomUUID()).toString());
        var wrongUser=backfill(application);((ObjectNode)wrongUser.path("voucher")).put("customerId",99);events.accept(wrongUser.toString());
        var wrongBooking=backfill(application);((ObjectNode)wrongBooking.path("voucher")).put("bookingId",2);events.accept(wrongBooking.toString());
        var malformed=backfill(application);((ObjectNode)malformed.path("voucher")).put("applicationId","not-a-uuid");events.accept(malformed.toString());
        var negative=backfill(application);((ObjectNode)negative.path("voucher")).put("voucherId",-1);events.accept(negative.toString());
        var empty=backfill(application);((ObjectNode)empty.path("voucher")).remove(java.util.List.of("bookingCode","customerName","customerPhone"));events.accept(empty.toString());
        assertThat(jdbc.queryForMap("SELECT customer_name,customer_phone,booking_code FROM voucher_usages").values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_inbox",Long.class)).isEqualTo(7);
    }

    @Test void malformedPayloadErrorNeverEchoesCustomerData() {
        assertThatThrownBy(()->events.accept("{\"customerName\":\"Secret Person\",\"customerPhone\":\"0912345678\""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Secret").hasMessageNotContaining("0912345678");
    }

    @Test void invalidTimestampOrFailedCounterRollsBackHistoryInboxAndApplication() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        var event=applied(application,UUID.randomUUID());((ObjectNode)event.path("voucher")).put("usedAt","not-a-date");
        assertThatThrownBy(()->events.accept(event.toString())).isInstanceOf(java.time.DateTimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_usages",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_inbox",Long.class)).isZero();
        jdbc.update("UPDATE vouchers SET used_count=usage_limit");
        ((ObjectNode)event.path("voucher")).put("usedAt",NOW.toString());
        assertThatThrownBy(()->events.accept(event.toString())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_usages",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_inbox",Long.class)).isZero();
        assertThat(applications.booking(1,false).orElseThrow().status()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(10);
    }

    @Test void paidUsageStoresCustomerSnapshotAndNormalizedPhone() throws Exception {
        voucher("SAVE",10);var application=apply(1,10);
        var event=applied(application,UUID.randomUUID());
        ((ObjectNode)event.path("voucher")).put("customerName","  Nguyen Van A\n").put("customerPhone","+84 912 345 678").put("bookingCode","FC00000001");
        events.accept(event.toString());
        var usage=jdbc.queryForMap("SELECT * FROM voucher_usages");
        assertThat(usage.get("customer_name")).isEqualTo("Nguyen Van A");
        assertThat(usage.get("customer_phone")).isEqualTo("+84 912 345 678");
        assertThat(usage.get("customer_phone_digits")).isEqualTo("0912345678");
        assertThat(usage.get("booking_code")).isEqualTo("FC00000001");
    }

    @Test void legacyEventsWithoutSnapshotAndMalformedSnapshotStillRecordUsage() throws Exception {
        voucher("SAVE",10);
        var legacy=apply(1,10);events.accept(applied(legacy,UUID.randomUUID()).toString());
        var other=apply(2,20);
        var malformed=applied(other,UUID.randomUUID());
        ((ObjectNode)malformed.path("voucher")).put("customerName",123).put("customerPhone","x".repeat(40)).put("bookingCode","TOO-LONG-CODE");
        events.accept(malformed.toString());
        var rows=jdbc.queryForList("SELECT customer_name,customer_phone,customer_phone_digits,booking_code FROM voucher_usages");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row->assertThat(row.values()).containsOnlyNulls());
        assertThat(jdbc.queryForObject("SELECT used_count FROM vouchers",Integer.class)).isEqualTo(2);
    }
}
