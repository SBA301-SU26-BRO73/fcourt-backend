package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
class BookingPosCharges {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StaffAuthority authority;
    private final byte[] credential;
    private final String settlementToken;
    private final RestClient pos;
    private final TransactionTemplate tx;
    private final boolean enabled;

    BookingPosCharges(
            JdbcTemplate jdbc,
            ObjectMapper json,
            StaffAuthority authority,
            RestClient.Builder builder,
            org.springframework.transaction.PlatformTransactionManager manager,
            @Value("${POS_BOOKING_SERVICE_TOKEN:}") String credential,
            @Value("${POS_SETTLEMENT_SERVICE_TOKEN:}") String settlement,
            @Value("${POS_URL:http://localhost:8091}") String url,
            @Value("${booking.pos-dispatch-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.authority = authority;
        this.credential = credential.getBytes(StandardCharsets.UTF_8);
        this.settlementToken = settlement;
        this.enabled = enabled;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        pos = builder.clone().requestFactory(factory).baseUrl(url).build();
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    Context context(Jwt jwt, long id) {
        var rows = jdbc.queryForList("SELECT branch_id,status FROM bookings WHERE id=?", id);
        if (rows.isEmpty()) throw rejected();
        var row = rows.getFirst();
        long branch = ((Number) row.get("branch_id")).longValue();
        authority.require(jwt, branch, BookingQueries.actor(jwt));
        return new Context(id, branch, (String) row.get("status"));
    }

    @Transactional(timeout = 15)
    Charge attach(String bearer, long booking, String key, Request request) {
        if (credential.length < 32)
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "POS authority is not configured");
        if (bearer == null
                || !MessageDigest.isEqual(
                        ("Bearer " + new String(credential, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8),
                        bearer.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "POS service credential required");
        if (request.orderCode() == null
                || !request.orderCode().equals(key)
                || !request.orderCode().matches("POS-[0-9a-fA-F-]{36}")
                || request.branchId() <= 0
                || request.cashierUserId() <= 0
                || request.items() == null
                || request.items().isEmpty()
                || request.items().size() > 50) throw rejected();
        var sum = BigDecimal.ZERO;
        var ids = new HashSet<Long>();
        for (var item : request.items()) {
            if (item == null
                    || item.productId() <= 0
                    || !ids.add(item.productId())
                    || item.productName() == null
                    || item.productName().isBlank()
                    || item.productName().length() > 200
                    || item.unit() == null
                    || item.unit().isBlank()
                    || item.unit().length() > 30
                    || item.quantity() < 1
                    || item.quantity() > 10000
                    || !money(item.priceAtOrder())
                            .multiply(BigDecimal.valueOf(item.quantity()))
                            .equals(money(item.lineTotal()))) throw rejected();
            sum = sum.add(item.lineTotal());
        }
        if (money(request.amount()).compareTo(sum) != 0) throw rejected();
        String fingerprint = hash(Arrays.asList(booking, request));
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "pos:" + key);
        var replay =
                jdbc.queryForList(
                        "SELECT fingerprint FROM booking_pos_charges WHERE order_code=?", key);
        if (!replay.isEmpty()) {
            if (!fingerprint.equals(replay.getFirst().get("fingerprint"))) throw rejected();
            return new Charge(key, booking, request.branchId(), request.amount(), "ACCEPTED");
        }
        var rows =
                jdbc.queryForList(
                        "SELECT branch_id,status FROM bookings WHERE id=? FOR UPDATE", booking);
        if (rows.isEmpty()
                || ((Number) rows.getFirst().get("branch_id")).longValue() != request.branchId()
                || !Set.of("CONFIRMED", "CHECKED_IN").contains(rows.getFirst().get("status")))
            throw rejected();
        jdbc.update(
                "INSERT INTO"
                    + " booking_pos_charges(order_code,booking_id,branch_id,cashier_user_id,amount,fingerprint,items)"
                    + " VALUES (?,?,?,?,?,?,?::jsonb)",
                key,
                booking,
                request.branchId(),
                request.cashierUserId(),
                request.amount(),
                fingerprint,
                encode(request.items()));
        return new Charge(key, booking, request.branchId(), request.amount(), "ACCEPTED");
    }

    BigDecimal pending(long booking) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount),0) FROM booking_pos_charges WHERE booking_id=? AND NOT"
                    + " settled",
                BigDecimal.class,
                booking);
    }

    void enqueue(long booking, long branch, Instant paidAt, String key) {
        var orders =
                jdbc.query(
                        "SELECT order_code,amount FROM booking_pos_charges WHERE booking_id=? AND"
                            + " NOT settled ORDER BY order_code",
                        (r, n) -> new PaidOrder(r.getString(1), r.getBigDecimal(2)),
                        booking);
        for (int offset = 0; offset < orders.size(); offset += 100) {
            var batch = orders.subList(offset, Math.min(offset + 100, orders.size()));
            UUID id = UUID.nameUUIDFromBytes((key + ":" + offset).getBytes(StandardCharsets.UTF_8));
            var settlement = new Settlement(id, booking, branch, paidAt, batch);
            jdbc.update(
                    "INSERT INTO booking_pos_settlement_outbox(event_id,payload) VALUES"
                        + " (?,?::jsonb)",
                    id,
                    encode(settlement));
        }
    }

    @Scheduled(fixedDelayString = "${booking.pos-dispatch-delay-ms:1000}")
    void drain() {
        if (!enabled || settlementToken.getBytes(StandardCharsets.UTF_8).length < 32) return;
        for (UUID id :
                jdbc.queryForList(
                        "SELECT event_id FROM booking_pos_settlement_outbox WHERE published_at IS"
                            + " NULL ORDER BY created_at LIMIT 10",
                        UUID.class)) {
            try {
                tx.executeWithoutResult(
                        ignored -> {
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT payload::text AS payload FROM"
                                                + " booking_pos_settlement_outbox WHERE event_id=?"
                                                + " AND published_at IS NULL FOR UPDATE SKIP"
                                                + " LOCKED",
                                            id);
                            if (rows.isEmpty()) return;
                            Settlement request;
                            try {
                                request =
                                        json.readValue(
                                                (String) rows.getFirst().get("payload"),
                                                Settlement.class);
                            } catch (Exception e) {
                                throw new IllegalStateException("Invalid settlement outbox");
                            }
                            pos.post()
                                    .uri("/api/internal/pos/settlements")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settlementToken)
                                    .body(request)
                                    .retrieve()
                                    .toBodilessEntity();
                            jdbc.update(
                                    "UPDATE booking_pos_settlement_outbox SET published_at=now()"
                                        + " WHERE event_id=?",
                                    id);
                            for (var order : request.orders())
                                jdbc.update(
                                        "UPDATE booking_pos_charges SET settled=true WHERE"
                                            + " order_code=?",
                                        order.orderCode());
                        });
            } catch (org.springframework.web.client.RestClientException unavailable) {
                /* Durable outbox retries after recovery; never roll back collected cash. */
            }
        }
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 2
                || value.compareTo(new BigDecimal("99999999.99")) > 0) throw rejected();
        return value.setScale(2);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode POS command");
        }
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint charge");
        }
    }

    private static ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "POS charge rejected");
    }

    record Context(long id, long branchId, String status) {}

    record Item(
            long productId,
            String productName,
            String unit,
            int quantity,
            BigDecimal priceAtOrder,
            BigDecimal lineTotal) {}

    record Request(
            String orderCode,
            long branchId,
            long cashierUserId,
            BigDecimal amount,
            List<Item> items) {}

    record Charge(
            String orderCode, long bookingId, long branchId, BigDecimal amount, String status) {}

    record PaidOrder(String orderCode, BigDecimal amount) {}

    record Settlement(
            UUID eventId, long bookingId, long branchId, Instant paidAt, List<PaidOrder> orders) {}
}

@RestController
class BookingPosController {
    private final BookingPosCharges service;

    BookingPosController(BookingPosCharges service) {
        this.service = service;
    }

    @GetMapping("/api/internal/bookings/{id}/pos-context")
    ResponseEntity<BookingPosCharges.Context> context(
            @org.springframework.security.core.annotation.AuthenticationPrincipal Jwt jwt,
            @PathVariable long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.context(jwt, id));
    }

    @PostMapping("/api/internal/bookings/{id}/pos-charges")
    ResponseEntity<BookingPosCharges.Charge> attach(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String bearer,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody BookingPosCharges.Request request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.attach(bearer, id, key, request));
    }
}
