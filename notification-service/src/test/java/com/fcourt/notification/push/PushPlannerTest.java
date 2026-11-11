package com.fcourt.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import org.junit.jupiter.api.Test;

class PushPlannerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PushPlanner planner = new PushPlanner();
    private EventEnvelope event(String type, String objectName, long customerId) {
        var root = json.createObjectNode();
        root.put("eventId", java.util.UUID.randomUUID().toString());
        root.put("type", type);
        var payload = json.createObjectNode();
        payload.put("bookingId", 42);
        if (customerId > 0) payload.put("customerId", customerId);
        root.set(objectName, payload);
        return new EventEnvelope(java.util.UUID.fromString(root.path("eventId").asText()), type, root);
    }
    @Test void plansConfirmationRejectionCancellationAndRefundPayloads() {
        var confirmed=planner.plan(event("PaymentConfirmedEvent", "payment", 7));
        assertThat(confirmed).extracting(PushPlan::type, PushPlan::userId, PushPlan::bookingId)
                .containsExactly("BOOKING_CONFIRMED", 7L, 42L);
        var rejected=planner.plan(event("PaymentRejectedEvent", "payment", 7));
        assertThat(rejected.type()).isEqualTo("BOOKING_CANCELLED");
        var cancelled=planner.plan(event("BookingCancelledEvent", "booking", 7));
        assertThat(cancelled.dedupEventId()).isEqualTo(rejected.dedupEventId());
        var refund=planner.plan(event("RefundCompletedEvent", "refund", 7));
        assertThat(refund).extracting(PushPlan::type, PushPlan::title, PushPlan::body)
                .containsExactly("REFUND_COMPLETED", "Đã hoàn tiền", "Khoản hoàn tiền của bạn đã hoàn tất.");
    }
    @Test void missingCustomerIdSilentlySkipsPush() {
        assertThat(planner.plan(event("PaymentConfirmedEvent", "payment", -1))).isNull();
    }
}
