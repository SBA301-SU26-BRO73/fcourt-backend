package com.fcourt.voucher;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
}
