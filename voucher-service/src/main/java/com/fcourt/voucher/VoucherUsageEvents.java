package com.fcourt.voucher;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="voucher.events-enabled",havingValue="true",matchIfMissing=true)
class VoucherUsageEvents {
    private final VoucherRedemptions applications;
    private final ObjectMapper json;
    private final VoucherBookingClient booking;
    private final VoucherUsageRepository history;
    VoucherUsageEvents(VoucherRedemptions applications, ObjectMapper json,
            VoucherBookingClient booking, VoucherUsageRepository history) {
        this.applications = applications; this.json = json;
        this.booking = booking; this.history = history;
    }

    @KafkaListener(topics={"fcourt.booking.events","fcourt.payment.events"})
    @Transactional
    public void accept(String payload) throws Exception {
        var event = json.readTree(payload);
        String type = event.path("type").asText();
        if (!java.util.Set.of("VoucherAppliedEvent", "BookingCancelledEvent", "PaymentRejectedEvent").contains(type)) return;
        UUID eventId = UUID.fromString(event.path("eventId").asText());
        var jdbc = applications.jdbc();
        if (jdbc.update("INSERT INTO voucher_inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 0) return;
        JsonNode data = event.path(type.equals("VoucherAppliedEvent") ? "voucher" : event.has("booking") ? "booking" : "payment");
        long bookingId = positive(data, data.has("bookingId") ? "bookingId" : "id");
        var found = applications.booking(bookingId, false);
        if (found.isEmpty()) {
            if (type.equals("VoucherAppliedEvent")) throw new IllegalStateException("Voucher application is not synchronized");
            return;
        }
        if (type.equals("BookingCancelledEvent") && booking.internal(bookingId).paymentFrozen()) return;
        applications.lockVoucher(found.get().voucherId());
        var application = applications.booking(bookingId, true).orElseThrow();
        if (positive(data, "branchId") != application.branchId()) throw invalid();
        if (!type.equals("VoucherAppliedEvent")) {
            if (data.hasNonNull("customerId") && positive(data, "customerId") != application.customerId()) throw invalid();
            if (data.has("amount") && !same(data, "amount", application.finalAmount())) throw invalid();
            applications.release(bookingId);
            return;
        }
        long paymentId = positive(data, "paymentId");
        if (!UUID.fromString(data.path("applicationId").asText()).equals(application.applicationId())
                || positive(data, "voucherId") != application.voucherId() || positive(data, "customerId") != application.customerId()
                || !same(data, "originalAmount", application.originalAmount()) || !same(data, "discountAmount", application.discountAmount())
                || !same(data, "finalAmount", application.finalAmount())) throw invalid();
        if ("APPLIED".equals(application.status())) {
            if (!Long.valueOf(paymentId).equals(application.paymentId())) throw invalid();
            return;
        }
        if (!"RESERVED".equals(application.status())) throw invalid();
        java.time.Instant usedAt = null;
        if (data.has("usedAt")) {
            if (!data.path("usedAt").isTextual()) throw invalid();
            usedAt = java.time.Instant.parse(data.path("usedAt").textValue());
        }
        history.record(application, paymentId, usedAt);
        if (jdbc.update("UPDATE vouchers SET used_count=used_count+1,updated_at=now() WHERE id=? AND used_count<usage_limit", application.voucherId()) != 1)
            throw new IllegalStateException("Reserved voucher quota is inconsistent");
        jdbc.update("UPDATE voucher_redemptions SET status='APPLIED',payment_id=?,updated_at=now() WHERE booking_id=?", paymentId, bookingId);
    }

    private long positive(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    private boolean same(JsonNode node, String field, BigDecimal expected) {
        return node.path(field).isNumber() && node.path(field).decimalValue().compareTo(expected) == 0;
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Voucher usage event identity or amount mismatch"); }
}
