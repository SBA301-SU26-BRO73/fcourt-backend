package com.fcourt.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class BookingVoucherEvents {
    private BookingVoucherEvents() {}

    static void confirmed(JdbcTemplate jdbc, ObjectMapper json, Map<String, Object> booking, JsonNode payment) {
        if (booking.get("voucher_application_id") == null || "CONFIRMED".equals(booking.get("payment_status"))) return;
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
        try {
            jdbc.update("INSERT INTO booking_outbox(event_id,booking_id,payload) VALUES (?,?,?::jsonb)",
                    eventId, booking.get("id"), json.writeValueAsString(Map.of("eventId", eventId,
                            "type", "VoucherAppliedEvent", "voucher", voucher)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
