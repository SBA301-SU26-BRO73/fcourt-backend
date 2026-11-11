package com.fcourt.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class BookingVoucherEvents {
    private BookingVoucherEvents() {}

    static void confirmed(JdbcTemplate jdbc, ObjectMapper json, Map<String, Object> booking, JsonNode payment) {
        if (booking.get("voucher_application_id") == null
                || !Set.of("PENDING_PAYMENT", "AWAITING_CONFIRMATION", "CANCELLED").contains(booking.get("status"))) return;
        UUID eventId = UUID.randomUUID();
        var voucher = new LinkedHashMap<String, Object>();
        voucher.put("applicationId", booking.get("voucher_application_id"));
        voucher.put("voucherId", booking.get("voucher_id"));
        voucher.put("bookingId", booking.get("id"));
        voucher.put("customerId", booking.get("customer_id"));
        voucher.put("branchId", booking.get("branch_id"));
        voucher.put("paymentId", payment.path("id").longValue());
        voucher.put("originalAmount", booking.get("original_amount"));
        voucher.put("discountAmount", booking.get("discount_amount"));
        voucher.put("finalAmount", booking.get("total_price"));
        // Additive display snapshot for the voucher usage history. Optional for consumers; never logged.
        putIfPresent(voucher, "bookingCode", booking.get("booking_code"));
        putIfPresent(voucher, "customerName", booking.get("customer_name"));
        putIfPresent(voucher, "customerPhone", booking.get("guest_phone"));
        if (payment.hasNonNull("confirmedAt")) {
            voucher.put("usedAt", java.time.Instant.parse(payment.path("confirmedAt").asText()).toString());
        }
        try {
            jdbc.update("INSERT INTO booking_outbox(event_id,booking_id,payload) VALUES (?,?,?::jsonb)",
                    eventId, booking.get("id"), json.writeValueAsString(Map.of("eventId", eventId,
                            "type", "VoucherAppliedEvent", "voucher", voucher)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) target.put(key, text.strip());
    }
}
