package com.fcourt.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fcourt.pos.service.PosDispatchWorker;
import com.fcourt.pos.service.PosCommands;
import com.fcourt.pos.persistence.PosStore;
import com.fcourt.pos.integration.PosUpstreams;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "pos.dispatch-enabled=false")
class PosIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final RSAKey SIGNING_KEY = key("auth");
    static final RSAKey FOREIGN_KEY = key("foreign");
    static final String SETTLEMENT_TOKEN = "pos-test-settlement-service-token-000000";
    static final String BOOKING_TOKEN = "pos-test-booking-service-token-000000";
    static final Map<String, Map<String, Object>> CHARGES = new ConcurrentHashMap<>();
    static volatile boolean active = true;
    static volatile int upstreamStatus;
    static volatile int chargeStatus;
    static volatile boolean failAfterCharge;
    static volatile boolean foreignBooking;
    static final HttpServer UPSTREAM = server();
    static final String URL = "http://localhost:" + UPSTREAM.getAddress().getPort();
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl); p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword); p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        p.add("pos.auth-url", () -> URL); p.add("pos.staff-url", () -> URL); p.add("pos.facility-url", () -> URL);
        p.add("pos.booking-url", () -> URL); p.add("pos.jwk-set-uri", () -> URL + "/jwks"); p.add("pos.issuer", () -> "fcourt-test");
        p.add("pos.settlement-token", () -> SETTLEMENT_TOKEN); p.add("pos.booking-service-token", () -> BOOKING_TOKEN);
        p.add("pos.events-topic", () -> "pos-test-events");
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PosDispatchWorker dispatch;
    @Autowired PosStore store;
    @Autowired PosCommands commands;
    @Autowired PosUpstreams upstream;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE products, orders RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE pos_settlement_events");
        active = true; upstreamStatus = chargeStatus = 0; failAfterCharge = foreignBooking = false; CHARGES.clear();
    }
    @AfterAll static void stop() { UPSTREAM.stop(0); }

    @Test void productCatalogStockAndImmutableCashInvoice() {
        long product = product(10);
        var catalog = call(HttpMethod.GET, "/api/staff/pos/products?category=drinks&query=wat", null, token("100", "STAFF"), null);
        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getBody().size()).isEqualTo(1);
        long began = System.nanoTime();
        var created = sell(UUID.randomUUID(), "CASH", null, List.of(item(product, 2)));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(10000);
        assertThat(created.getBody().path("totalAmount").asInt()).isEqualTo(20000);
        assertThat(created.getBody().path("status").asText()).isEqualTo("PAID");
        assertThat(stock(product)).isEqualTo(8);
        long order = created.getBody().path("id").asLong();
        var updated = call(HttpMethod.PUT, "/api/admin/pos/products/" + product,
                Map.of("name", "New name", "unit", "bottle", "category", "drinks", "price", 50000, "active", true), token("1", "ADMIN"), UUID.randomUUID());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        var invoice = call(HttpMethod.GET, "/api/staff/pos/orders/" + order, null, token("100", "STAFF"), null);
        assertThat(invoice.getBody().path("items").get(0).path("priceAtOrder").asInt()).isEqualTo(10000);
        assertThat(invoice.getBody().path("items").get(0).path("productName").asText()).isEqualTo("Water");
        assertThat(call(HttpMethod.GET, "/api/staff/pos/orders", null, token("100", "STAFF"), null).getBody().size()).isEqualTo(1);
        assertThat(invoice.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void stockAdjustmentAndProductCreationReplayWithoutDoubleMovement() {
        UUID key = UUID.randomUUID();
        var first = call(HttpMethod.POST, "/api/admin/pos/products", productBody(3), token("1", "ADMIN"), key);
        var replay = call(HttpMethod.POST, "/api/admin/pos/products", productBody(3), token("1", "ADMIN"), key);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        long id = first.getBody().path("id").asLong();
        UUID adjustment = UUID.randomUUID();
        Object body = Map.of("delta", 2, "reason", "restock");
        assertThat(call(HttpMethod.POST, "/api/admin/pos/products/" + id + "/stock-adjustments", body, token("1", "ADMIN"), adjustment).getStatusCode()).isEqualTo(HttpStatus.OK);
        call(HttpMethod.POST, "/api/admin/pos/products/" + id + "/stock-adjustments", body, token("1", "ADMIN"), adjustment);
        assertThat(stock(id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_stock_movements", Integer.class)).isEqualTo(2);
        assertThat(call(HttpMethod.POST, "/api/admin/pos/products/" + id + "/stock-adjustments", Map.of("delta", -6, "reason", "bad"), token("1", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void duplicateConcurrentSaleUsesOneOrderAndOneDecrement() throws Exception {
        long product = product(10); UUID key = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 6).mapToObj(i -> executor.submit(() -> sell(key, "CASH", null, List.of(item(product, 2))))).toList();
            for (var job : jobs) assertThat(job.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(stock(product)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test void competingSalesNeverMakeInventoryNegative() throws Exception {
        long product = product(5); int success = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = java.util.stream.IntStream.range(0, 12).mapToObj(i -> executor.submit(() -> sell(UUID.randomUUID(), "CASH", null, List.of(item(product, 1))))).toList();
            for (var job : jobs) {
                var status = job.get().getStatusCode(); assertThat(status).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
                if (status == HttpStatus.CREATED) success++;
            }
        }
        assertThat(success).isEqualTo(5); assertThat(stock(product)).isZero();
    }

    @Test void multipleProductFailureRollsBackEveryStockAndOrderLine() {
        long first = product(5); long second = product(0);
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(first, 2), item(second, 1))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(first)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test void forcedOutboxFailureRollsBackCashSale() {
        long id = product(5);
        jdbc.execute("ALTER TABLE pos_outbox ADD CONSTRAINT reject_event CHECK(order_id < 0)");
        try { assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); }
        finally { jdbc.execute("ALTER TABLE pos_outbox DROP CONSTRAINT reject_event"); }
        assertThat(stock(id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test void bookingChargeIsPendingUntilAttachedAndSettledWithIdempotentCallback() {
        long id = product(5); var response = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 2)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long order = response.getBody().path("id").asLong();
        assertThat(status(order)).isEqualTo("PENDING_CHARGE");
        dispatch.drain(); assertThat(status(order)).isEqualTo("ON_BOOKING");
        assertThat(CHARGES).hasSize(1);
        var settlement = settlement(response.getBody().path("orderCode").asText(), 20000, UUID.randomUUID());
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", settlement, SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", settlement, SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(order)).isEqualTo("PAID"); assertThat(stock(id)).isEqualTo(3);
    }

    @Test void ambiguousChargeResponseAndLocalFailureRetrySameImmutableCharge() {
        long id = product(5); var sale = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1)));
        long order = sale.getBody().path("id").asLong(); failAfterCharge = true;
        assertThatThrownBy(dispatch::drain).isInstanceOf(RuntimeException.class);
        assertThat(status(order)).isEqualTo("PENDING_CHARGE"); assertThat(CHARGES).hasSize(1);
        failAfterCharge = false;
        jdbc.execute("ALTER TABLE pos_outbox ADD CONSTRAINT reject_ack CHECK(kind <> 'CHARGE' OR published_at IS NULL)");
        try { assertThatThrownBy(dispatch::drain).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pos_outbox DROP CONSTRAINT reject_ack"); }
        dispatch.drain(); assertThat(status(order)).isEqualTo("ON_BOOKING"); assertThat(CHARGES).hasSize(1);
        assertThat(stock(id)).isEqualTo(4);
    }

    @Test void permanentChargeRejectionRestocksExactlyOnceAndCancelsInvoice() {
        long id = product(5); var sale = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 2)));
        long order = sale.getBody().path("id").asLong(); chargeStatus = 422;
        dispatch.drain(); dispatch.drain();
        assertThat(status(order)).isEqualTo("CANCELLED"); assertThat(stock(id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_stock_movements WHERE kind = 'RESTOCK'", Integer.class)).isEqualTo(1);
    }

    @Test void settlementWrongAmountBranchOrServiceCredentialCannotMarkPaid() {
        long id = product(5); var sale = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1)));
        long order = sale.getBody().path("id").asLong(); dispatch.drain();
        var invalid = settlement(sale.getBody().path("orderCode").asText(), 1, UUID.randomUUID());
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", invalid, SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", invalid, token("100", "STAFF"), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(order)).isEqualTo("ON_BOOKING");
    }

    @Test void foreignBranchAndInactiveStaffCannotReadOrSell() {
        long id = product(5); var sale = sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1)));
        assertThat(call(HttpMethod.GET, "/api/staff/pos/orders/" + sale.getBody().path("id").asLong(), null, token("101", "STAFF"), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/staff/pos/products", null, token("101", "STAFF"), null).getBody().size()).isZero();
        assertThat(call(HttpMethod.POST, "/api/staff/pos/orders", Map.of("paymentMethod", "CASH", "items", List.of(item(id, 1))), token("101", "STAFF"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        active = false;
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void foreignOwnerCannotCreateOrAdjustStock() {
        var request = new HashMap<>(productBody(5)); request.put("branchId", 8);
        assertThat(call(HttpMethod.POST, "/api/admin/pos/products", request, token("1", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long id = product(5);
        assertThat(call(HttpMethod.POST, "/api/admin/pos/products/" + id + "/stock-adjustments", Map.of("delta", 1, "reason", "bad"), token("2", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void changedCommandBodyDuplicateItemsAndClientPricesAreRejected() {
        long id = product(5); UUID key = UUID.randomUUID(); sell(key, "CASH", null, List.of(item(id, 1)));
        assertThat(sell(key, "CASH", null, List.of(item(id, 2))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1), item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/staff/pos/orders", Map.of("paymentMethod", "CASH", "items", List.of(item(id, 1)), "totalAmount", 1), token("100", "STAFF"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sell(UUID.randomUUID(), "BOOKING", null, List.of(item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest @ValueSource(strings = {"signature", "issuer", "audience", "expired", "subject", "no-exp"})
    void invalidSignedJwtIsRejected(String fault) {
        String bearer = signed(fault.equals("subject") ? "bad" : "100", "STAFF", fault.equals("issuer") ? "foreign" : "fcourt-test",
                fault.equals("audience") ? "other" : "fcourt", fault.equals("no-exp") ? null : Instant.now().plusSeconds(fault.equals("expired") ? -3600 : 300),
                fault.equals("signature") ? FOREIGN_KEY : SIGNING_KEY);
        assertThat(call(HttpMethod.GET, "/api/staff/pos/products", null, bearer, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @ParameterizedTest @ValueSource(strings = {"CUSTOMER", "ADMIN", "SUPER_ADMIN"})
    void wrongRoleCannotUseStaffPos(String role) {
        assertThat(call(HttpMethod.GET, "/api/staff/pos/products", null, token("100", role), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @ParameterizedTest @ValueSource(ints = {400, 500, 503})
    void dependencyFailuresFailClosed(int status) {
        upstreamStatus = status;
        assertThat(call(HttpMethod.GET, "/api/staff/pos/products", null, token("100", "STAFF"), null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void healthHasNoDetailsAndAnonymousBusinessAccessIsDenied() {
        var health = call(HttpMethod.GET, "/actuator/health", null, null, null);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().size()).isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/api/staff/pos/products", null, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 10001})
    void invalidQuantityCannotMutateStock(int quantity) {
        long id = product(5);
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, quantity))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stock(id)).isEqualTo(5);
    }

    @Test void inactiveProductOrForeignBookingCannotBeSold() {
        long id = product(5); foreignBooking = true;
        assertThat(sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        foreignBooking = false; jdbc.update("UPDATE products SET active = false WHERE id = ?", id);
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stock(id)).isEqualTo(5);
    }

    @Test void monetaryOverflowRejectsWholeSale() {
        long id = product(5); jdbc.update("UPDATE products SET price = 99999999.99 WHERE id = ?", id);
        assertThat(sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 2))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stock(id)).isEqualTo(5);
    }

    @Test void settlementEventIdentityCannotBeReusedWithDifferentMetadata() {
        long id = product(5); var sale = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1))); dispatch.drain();
        UUID event = UUID.randomUUID(); String code = sale.getBody().path("orderCode").asText();
        var payload = settlement(code, 10000, event);
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", payload, SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", settlement(code, 1, event), SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void brokerFailureRetainsEventWithoutUndoingCashSaleAndRealKafkaRecovers() throws Exception {
        long id = product(5); var sale = sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 1)));
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> failedKafka = org.mockito.Mockito.mock(KafkaTemplate.class);
        org.mockito.Mockito.when(failedKafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        var failed = new PosDispatchWorker(jdbc, store, commands, upstream, failedKafka, transactions, "pos-test-events", false);
        assertThatThrownBy(failed::drain).isInstanceOf(RuntimeException.class);
        assertThat(stock(id)).isEqualTo(4);
        assertThat(status(sale.getBody().path("id").asLong())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_outbox WHERE published_at IS NULL", Integer.class)).isEqualTo(1);
        dispatch.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_outbox WHERE published_at IS NULL", Integer.class)).isZero();
        String code = sale.getBody().path("orderCode").asText();
        boolean found = false;
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest",
                "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class))) {
            consumer.subscribe(List.of("pos-test-events")); Instant deadline = Instant.now().plusSeconds(20);
            while (!found && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(java.time.Duration.ofMillis(250))) {
                    if (code.equals(JSON.readTree(record.value()).path("order").path("orderCode").asText())) {
                        assertThat(record.key()).isEqualTo(code);
                        assertThat(record.value()).doesNotContain("Bearer", BOOKING_TOKEN);
                        found = true;
                    }
                }
            }
        }
        assertThat(found).isTrue();
    }

    @Test void decimalPriceTotalsRemainExact() {
        long id = product(5); jdbc.update("UPDATE products SET price = 10.25 WHERE id = ?", id);
        var sale = sell(UUID.randomUUID(), "CASH", null, List.of(item(id, 3)));
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sale.getBody().path("totalAmount").decimalValue()).isEqualByComparingTo("30.75");
        assertThat(sale.getBody().path("items").get(0).path("lineTotal").decimalValue()).isEqualByComparingTo("30.75");
    }

    @Test void reversedCartOrderUsesConsistentProductLockOrdering() throws Exception {
        long first = product(2), second = product(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> sell(UUID.randomUUID(), "CASH", null, List.of(item(first, 1), item(second, 1))));
            var b = executor.submit(() -> sell(UUID.randomUUID(), "CASH", null, List.of(item(second, 1), item(first, 1))));
            assertThat(a.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(b.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(stock(first)).isZero(); assertThat(stock(second)).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {409, 500, 503})
    void ambiguousBookingFailuresNeverRestockOrPretendPaymentSucceeded(int status) {
        long id = product(5); var sale = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1))); chargeStatus = status;
        assertThatThrownBy(dispatch::drain).isInstanceOf(RuntimeException.class);
        assertThat(status(sale.getBody().path("id").asLong())).isEqualTo("PENDING_CHARGE");
        assertThat(stock(id)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_stock_movements WHERE kind = 'RESTOCK'", Integer.class)).isZero();
    }

    @Test void settlementBatchRollsBackEarlierOrdersWhenLaterAmountConflicts() {
        long id = product(5);
        var one = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1))).getBody();
        var two = sell(UUID.randomUUID(), "BOOKING", 42L, List.of(item(id, 1))).getBody();
        dispatch.drain();
        var ordered = new ArrayList<>(List.of(one, two)); ordered.sort(Comparator.comparing(o -> o.path("orderCode").asText()));
        var request = Map.of("eventId", UUID.randomUUID(), "bookingId", 42, "branchId", 7, "paidAt", Instant.now().toString(),
                "orders", List.of(Map.of("orderCode", ordered.getFirst().path("orderCode").asText(), "amount", 10000),
                        Map.of("orderCode", ordered.getLast().path("orderCode").asText(), "amount", 1)));
        assertThat(call(HttpMethod.POST, "/api/internal/pos/settlements", request, SETTLEMENT_TOKEN, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(one.path("id").asLong())).isEqualTo("ON_BOOKING");
        assertThat(status(two.path("id").asLong())).isEqualTo("ON_BOOKING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_settlement_events", Integer.class)).isZero();
    }

    @Test void zeroStockAdjustmentIsRejected() {
        long id = product(5);
        assertThat(call(HttpMethod.POST, "/api/admin/pos/products/" + id + "/stock-adjustments", Map.of("delta", 0, "reason", "invalid"),
                token("1", "ADMIN"), UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stock(id)).isEqualTo(5);
    }

    long product(int quantity) {
        var response = call(HttpMethod.POST, "/api/admin/pos/products", productBody(quantity), token("1", "ADMIN"), UUID.randomUUID());
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }
    static Map<String, Object> productBody(int quantity) { return Map.of("branchId", 7, "name", "Water", "unit", "bottle", "category", "drinks", "price", 10000, "stockQuantity", quantity); }
    static Map<String, Object> item(long id, int quantity) { return Map.of("productId", id, "quantity", quantity); }
    ResponseEntity<JsonNode> sell(UUID key, String method, Long booking, List<Map<String, Object>> items) {
        var request = new HashMap<String, Object>(); request.put("paymentMethod", method); request.put("items", items);
        if (booking != null) request.put("bookingId", booking);
        return call(HttpMethod.POST, "/api/staff/pos/orders", request, token("100", "STAFF"), key);
    }
    Object settlement(String code, int amount, UUID event) {
        return Map.of("eventId", event, "bookingId", 42, "branchId", 7, "paidAt", Instant.now().toString(),
                "orders", List.of(Map.of("orderCode", code, "amount", amount)));
    }
    int stock(long id) { return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, id); }
    String status(long id) { return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id); }
    ResponseEntity<JsonNode> call(HttpMethod method, String uri, Object body, String bearer, UUID key) {
        var headers = new HttpHeaders(); if (bearer != null) headers.setBearerAuth(bearer);
        if (key != null) headers.set("Idempotency-Key", key.toString());
        return http.exchange(uri, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    static String token(String actor, String role) { return signed(actor, role, "fcourt-test", "fcourt", Instant.now().plusSeconds(300), SIGNING_KEY); }
    static RSAKey key(String id) { try { return new RSAKeyGenerator(2048).keyID(id).generate(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String signed(String actor, String role, String issuer, String audience, Instant expires, RSAKey key) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                    .subject(actor).issuer(issuer).audience(audience).expirationTime(expires == null ? null : Date.from(expires)).claim("roles", List.of(role)).build());
            jwt.sign(new RSASSASigner(key)); return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); server.createContext("/", PosIntegrationTest::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); server.start(); return server;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (path.equals("/jwks")) { send(x, 200, new JWKSet(SIGNING_KEY.toPublicJWK()).toJSONObject()); return; }
        if (upstreamStatus != 0) { send(x, upstreamStatus, Map.of()); return; }
        try {
            if (path.endsWith("/pos-charges")) {
                if (!Objects.equals(x.getRequestHeaders().getFirst("Authorization"), "Bearer " + BOOKING_TOKEN)) { send(x, 401, Map.of()); return; }
                if (chargeStatus != 0) { send(x, chargeStatus, Map.of()); return; }
                JsonNode request = JSON.readTree(x.getRequestBody()); String code = request.path("orderCode").asText();
                var result = CHARGES.computeIfAbsent(code, ignored -> Map.of("orderCode", code, "bookingId", 42, "branchId", 7,
                        "amount", request.path("amount").decimalValue(), "status", "ACCEPTED"));
                send(x, failAfterCharge ? 500 : 200, result); return;
            }
            var claims = SignedJWT.parse(x.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet();
            long id = Long.parseLong(claims.getSubject());
            if (path.equals("/api/staff/me")) send(x, 200, Map.of("id", 1, "userId", id, "branchId", id == 101 ? 8 : 7, "active", active, "fullName", "Staff"));
            else if (path.equals("/api/auth/me")) send(x, 200, Map.of("id", id, "role", "ADMIN", "status", active ? "ACTIVE" : "INACTIVE"));
            else if (path.startsWith("/api/admin/branches/")) {
                long branch = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                send(x, 200, Map.of("id", branch, "adminId", branch == 7 ? 1 : 2, "status", "ACTIVE"));
            } else if (path.endsWith("/pos-context")) send(x, 200, Map.of("id", 42, "branchId", foreignBooking ? 8 : 7, "status", "CHECKED_IN"));
            else send(x, 404, Map.of());
        } catch (Exception e) { send(x, 500, Map.of()); }
    }
    static void send(HttpExchange x, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length); try (var out = x.getResponseBody()) { out.write(bytes); }
    }
}
