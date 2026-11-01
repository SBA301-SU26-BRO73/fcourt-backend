package com.fcourt.reporting.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Translates upstream envelopes into booking facts.
 *
 * <ul>
 *   <li>{@code fcourt.payment.events}: {@code {eventId, type, payment:{bookingId, branchId, amount, checkout:{date, slots}}}}
 *       (payment-service SCRUM-34/36/38).</li>
 *   <li>{@code fcourt.staff.events}: {@code {eventId, type, version, data:{booking:{...}, actorId, occurredAt}}}
 *       (staff-operations-service SCRUM-28/30/65).</li>
 * </ul>
 * booking-service publishes no events yet; its events can be mapped here when it exists.
 */
@Component
public class EventTranslator {
    /** Status after the event; revenue is recognized only by money events (confirmation, cash walk-in). */
    public record BookingFact(String eventId, String type, long bookingId, long branchId, String status,
                              BigDecimal amount, LocalDate bookingDate, int slotCount,
                              BigDecimal revenue, Instant revenueAt) { }

    public record Ignored(String eventId, String type) { }

    private final ObjectMapper json;

    public EventTranslator(ObjectMapper json) {
        this.json = json;
    }

    /** @return a fact, or {@link Ignored} for event types the dashboard does not use */
    public Object payment(String payload, Instant recordTime) {
        JsonNode root = parse(payload);
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        JsonNode payment = root.path("payment");
        String status = switch (type) {
            case "PaymentSubmittedEvent" -> "PENDING";
            case "PaymentConfirmedEvent" -> "CONFIRMED";
            case "PaymentRejectedEvent", "BookingCancelledEvent" -> "CANCELLED";
            default -> null;
        };
        if (status == null) return new Ignored(eventId, type);
        BigDecimal amount = money(payment.path("amount"));
        JsonNode checkout = payment.path("checkout");
        boolean confirmed = "CONFIRMED".equals(status);
        return new BookingFact(eventId, type, positiveId(payment, "bookingId"), positiveId(payment, "branchId"),
                status, amount, date(checkout.path("date")), checkout.path("slots").size(),
                confirmed ? amount : BigDecimal.ZERO, confirmed ? recordTime : null);
    }

    public Object staff(String payload, Instant recordTime) {
        JsonNode root = parse(payload);
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        JsonNode data = root.path("data");
        JsonNode booking = data.path("booking");
        long bookingId = switch (type) {
            case "WalkInBookingCreatedEvent", "BookingCheckedInEvent", "BookingCompletedEvent" ->
                    positiveId(booking, "bookingId");
            default -> -1;
        };
        if (bookingId < 0) return new Ignored(eventId, type);
        long branchId = positiveId(booking, "branchId");
        if ("WalkInBookingCreatedEvent".equals(type)) {
            BigDecimal amount = money(booking.path("amount"));
            Instant occurredAt = instant(data.path("occurredAt"), recordTime);
            return new BookingFact(eventId, type, bookingId, branchId, "CONFIRMED", amount,
                    date(booking.path("date")), booking.path("invoice").path("items").size(), amount, occurredAt);
        }
        // Check-in / completion confirm an existing booking; money was recognized by its payment event.
        return new BookingFact(eventId, type, bookingId, branchId, "CONFIRMED", BigDecimal.ZERO,
                date(booking.path("date")), 0, BigDecimal.ZERO, null);
    }

    private JsonNode parse(String payload) {
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject()) throw new InvalidEventException("Event must be a JSON object");
            return root;
        } catch (JsonProcessingException error) {
            throw new InvalidEventException("Event is not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 100) {
            throw new InvalidEventException("Missing or invalid " + field);
        }
        return value.asText();
    }

    private static long positiveId(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong() || value.asLong() <= 0) throw new InvalidEventException("Missing or invalid " + field);
        return value.asLong();
    }

    private static BigDecimal money(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return BigDecimal.ZERO;
        if (!value.isNumber() || value.decimalValue().signum() < 0) throw new InvalidEventException("Invalid amount");
        return value.decimalValue();
    }

    private static LocalDate date(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            return LocalDate.parse(value.asText());
        } catch (DateTimeParseException error) {
            throw new InvalidEventException("Invalid booking date");
        }
    }

    private static Instant instant(JsonNode value, Instant fallback) {
        if (value.isMissingNode() || value.isNull()) return fallback;
        try {
            return value.isNumber() ? Instant.ofEpochMilli((long) (value.asDouble() * 1000)) : Instant.parse(value.asText());
        } catch (DateTimeParseException error) {
            throw new InvalidEventException("Invalid occurredAt");
        }
    }
}
