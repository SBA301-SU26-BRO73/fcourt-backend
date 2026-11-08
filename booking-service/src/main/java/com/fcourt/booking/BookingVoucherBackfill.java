package com.fcourt.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Re-emits the display snapshot (booking code, customer name, phone) of already paid voucher bookings so that
 * voucher-service can fill usage rows recorded before the snapshot existed. Cursor based and bounded;
 * safe to repeat because the consumer only fills NULL columns. Never logs customer data.
 */
@Service
class BookingVoucherBackfill {
    static final int MAX_BATCH = 100;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    BookingVoucherBackfill(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    record Result(int emitted, long lastBookingId, boolean hasMore) {}

    @Transactional
    Result replay(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > MAX_BATCH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterId or limit is invalid");
        var rows = jdbc.queryForList("""
                SELECT id,voucher_application_id,voucher_id,customer_id,branch_id,booking_code,customer_name,guest_phone
                FROM bookings WHERE id>? AND voucher_application_id IS NOT NULL AND payment_status='CONFIRMED'
                ORDER BY id LIMIT ?
                """, afterId, limit + 1);
        boolean more = rows.size() > limit;
        var batch = more ? rows.subList(0, limit) : rows;
        int emitted = 0;
        long last = afterId;
        for (var row : batch) {
            last = ((Number) row.get("id")).longValue();
            var voucher = new LinkedHashMap<String, Object>();
            voucher.put("applicationId", row.get("voucher_application_id"));
            voucher.put("voucherId", row.get("voucher_id"));
            voucher.put("bookingId", row.get("id"));
            voucher.put("customerId", row.get("customer_id"));
            voucher.put("branchId", row.get("branch_id"));
            put(voucher, "bookingCode", row.get("booking_code"));
            put(voucher, "customerName", row.get("customer_name"));
            put(voucher, "customerPhone", row.get("guest_phone"));
            if (!voucher.containsKey("customerName") && !voucher.containsKey("customerPhone")) continue;
            if (voucher.get("customerId") == null) continue; // voucher usages always belong to a customer account
            UUID eventId = UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO booking_outbox(event_id,booking_id,payload) VALUES (?,?,?::jsonb)", eventId, row.get("id"),
                        json.writeValueAsString(Map.of("eventId", eventId, "type", "VoucherSnapshotBackfillEvent", "voucher", voucher)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
            emitted++;
        }
        return new Result(emitted, last, more);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) target.put(key, text.strip());
    }
}

@RestController
class BookingVoucherBackfillController {
    private final BookingVoucherBackfill backfill;
    private final BookingVoucherBridge bridge;
    BookingVoucherBackfillController(BookingVoucherBackfill backfill, BookingVoucherBridge bridge) { this.backfill = backfill; this.bridge = bridge; }

    @PostMapping("/api/internal/bookings/voucher-snapshot-backfill")
    ResponseEntity<BookingVoucherBackfill.Result> run(@RequestHeader(value = "X-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "50") int limit) {
        bridge.authenticate(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(backfill.replay(afterId, limit));
    }
}
