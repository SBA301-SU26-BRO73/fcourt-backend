package com.fcourt.notification.push;

import com.fcourt.notification.event.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PushPlanner {
    public PushPlan plan(EventEnvelope event) {
        var root = event.root();
        return switch (event.type()) {
            case "PaymentConfirmedEvent" -> booking(event, root.path("payment"), "BOOKING_CONFIRMED",
                    "Đặt sân thành công", "Đặt sân của bạn đã được xác nhận.", false);
            case "PaymentRejectedEvent", "BookingCancelledEvent" -> {
                var booking = root.path("booking").isObject() ? root.path("booking") : root.path("payment");
                long bookingId = id(booking.path("bookingId"));
                long user = optionalId(booking.path("customerId"));
                // Share the same cancellation key across the payment and booking topics, as EmailPlanner does.
                UUID stable = UUID.nameUUIDFromBytes(("booking-cancelled:" + bookingId).getBytes(StandardCharsets.UTF_8));
                yield user < 1 ? null : new PushPlan(stable, user, bookingId, "BOOKING_CANCELLED",
                        "Đặt sân đã hủy", "Đặt sân của bạn đã được hủy.");
            }
            case "RefundCompletedEvent" -> booking(event, root.path("refund"), "REFUND_COMPLETED",
                    "Đã hoàn tiền", "Khoản hoàn tiền của bạn đã hoàn tất.", false);
            default -> null;
        };
    }

    private PushPlan booking(EventEnvelope event, com.fasterxml.jackson.databind.JsonNode source, String type,
                            String title, String body, boolean ignored) {
        long bookingId = id(source.path("bookingId"));
        long user = optionalId(source.path("customerId"));
        return user < 1 ? null : new PushPlan(event.eventId(), user, bookingId, type, title, body);
    }

    private long optionalId(com.fasterxml.jackson.databind.JsonNode node) {
        return node.canConvertToLong() && node.asLong() > 0 ? node.asLong() : -1;
    }

    private long id(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.canConvertToLong() || node.asLong() <= 0) throw new IllegalArgumentException("Push event has no valid bookingId");
        return node.asLong();
    }
}
