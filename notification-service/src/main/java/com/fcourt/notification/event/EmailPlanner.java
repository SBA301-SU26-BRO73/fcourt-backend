package com.fcourt.notification.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fcourt.notification.mail.OutgoingEmail;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns events into emails. Consumer contract (see the README):
 * <ul>
 *   <li>{@code OwnerApprovedEvent} and {@code PasswordResetRequestedEvent} (fcourt.auth.events) carry
 *       {@code data.email} and {@code data.fullName}.</li>
 *   <li>{@code PaymentConfirmedEvent}, {@code PaymentRejectedEvent} and {@code BookingCancelledEvent}
 *       (fcourt.payment.events, fcourt.booking.events) need a top level
 *       {@code recipient: {email, fullName}}. The producers do not send it yet, so those emails are
 *       recorded as skipped until they do.</li>
 * </ul>
 */
@Component
public class EmailPlanner {
    static final String OWNER_APPROVED = "OwnerApprovedEvent";
    static final String PASSWORD_RESET = "PasswordResetRequestedEvent";
    static final String PAYMENT_CONFIRMED = "PaymentConfirmedEvent";
    static final String PAYMENT_REJECTED = "PaymentRejectedEvent";
    static final String BOOKING_CANCELLED = "BookingCancelledEvent";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s<>,;]+@[^@\\s<>,;]+$");
    private static final String DEFAULT_NAME = "Quý khách";

    private final String resetPasswordUrl;

    public EmailPlanner(@Value("${notification.links.reset-password-url}") String resetPasswordUrl) {
        this.resetPasswordUrl = resetPasswordUrl;
    }

    public EmailPlan plan(EventEnvelope event) {
        return switch (event.type()) {
            case OWNER_APPROVED -> ownerApproved(event);
            case PASSWORD_RESET -> passwordReset(event);
            case PAYMENT_CONFIRMED -> bookingConfirmed(event);
            case PAYMENT_REJECTED -> bookingRejected(event);
            case BOOKING_CANCELLED -> bookingCancelled(event);
            default -> new EmailPlan.Ignore();
        };
    }

    private EmailPlan ownerApproved(EventEnvelope event) {
        JsonNode data = event.root().path("data");
        String key = "owner-approved:" + requiredId(data.path("userId"), "data.userId", event);
        return build(address(data.path("email")), key, "Hồ sơ chủ sân FCOURT đã được duyệt", "owner-approved",
                Map.of("name", name(data.path("fullName"))));
    }

    private EmailPlan passwordReset(EventEnvelope event) {
        JsonNode data = event.root().path("data");
        String token = data.path("token").asText("");
        if (token.isBlank()) {
            throw new PoisonEventException("PasswordResetRequestedEvent has no token");
        }
        String link = resetPasswordUrl + (resetPasswordUrl.contains("?") ? "&" : "?")
                + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        var model = new LinkedHashMap<String, String>();
        model.put("name", name(data.path("fullName")));
        model.put("resetLink", link);
        model.put("expiresAt", dateTime(data.path("expiresAt"), event));
        // No business key: every request is legitimate and the eventId already dedupes redelivery.
        return build(address(data.path("email")), null, "Đặt lại mật khẩu FCOURT", "password-reset", model);
    }

    private EmailPlan bookingConfirmed(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), "payment.bookingId", event);
        JsonNode checkout = payment.path("checkout");
        String code = bookingCode(checkout, bookingId);
        var model = new LinkedHashMap<String, String>();
        model.put("name", recipientName(event, checkout));
        model.put("bookingCode", code);
        model.put("details", checkoutDetails(checkout, event));
        model.put("amount", money(payment.path("amount")));
        model.put("ticketCode", payment.path("checkInCode").asText("—"));
        return build(recipientAddress(event), "booking-confirmed:" + bookingId,
                "Xác nhận đặt sân " + code, "booking-confirmed", model);
    }

    private EmailPlan bookingRejected(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), "payment.bookingId", event);
        JsonNode checkout = payment.path("checkout");
        String detail = payment.path("rejectionReason").asText("");
        return cancelled(event, bookingId, checkout, "Thanh toán bị từ chối" + (detail.isBlank() ? "" : ": " + detail),
                checkoutDetails(checkout, event), "");
    }

    private EmailPlan bookingCancelled(EventEnvelope event) {
        JsonNode booking = event.root().path("booking");
        if (booking.isObject()) {
            // Published by booking-service when the customer cancels.
            long bookingId = requiredId(booking.path("bookingId"), "booking.bookingId", event);
            BigDecimal refund = booking.path("refundAmount").isNumber()
                    ? booking.path("refundAmount").decimalValue() : BigDecimal.ZERO;
            String note = refund.signum() > 0 ? "Số tiền hoàn lại dự kiến: " + money(booking.path("refundAmount")) : "";
            return cancelled(event, bookingId, booking, booking.path("reason").asText("Đơn đặt sân đã được hủy"),
                    bookingSlots(booking, event), note);
        }
        // Published by payment-service when an unpaid checkout expires.
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), "payment.bookingId", event);
        JsonNode checkout = payment.path("checkout");
        return cancelled(event, bookingId, checkout, "Đơn đặt sân đã hết hạn thanh toán",
                checkoutDetails(checkout, event), "");
    }

    private EmailPlan cancelled(EventEnvelope event, long bookingId, JsonNode source, String reason, String details,
                                String note) {
        String code = bookingCode(source, bookingId);
        var model = new LinkedHashMap<String, String>();
        model.put("name", recipientName(event, source));
        model.put("bookingCode", code);
        model.put("reason", reason);
        model.put("details", details);
        model.put("note", note);
        // The customer cancelling, payment expiry and a rejected payment all end the same booking.
        return build(recipientAddress(event), "booking-cancelled:" + bookingId,
                "Đơn đặt sân " + code + " đã bị hủy", "booking-cancelled", model);
    }

    private EmailPlan build(String to, String businessKey, String subject, String template,
                            Map<String, String> model) {
        if (to == null) {
            return new EmailPlan.NoRecipient(businessKey);
        }
        return new EmailPlan.Send(new OutgoingEmail(to, singleLine(subject), template, model), businessKey);
    }

    private static String recipientAddress(EventEnvelope event) {
        return address(event.root().path("recipient").path("email"));
    }

    private static String recipientName(EventEnvelope event, JsonNode source) {
        String fromRecipient = event.root().path("recipient").path("fullName").asText("");
        if (!fromRecipient.isBlank()) {
            return fromRecipient.strip();
        }
        return name(source.path("customerName"));
    }

    /** Only well-formed single addresses pass, which also rules out header injection. */
    private static String address(JsonNode node) {
        String value = node.asText("").strip();
        return ADDRESS.matcher(value).matches() ? value : null;
    }

    private static String name(JsonNode node) {
        String value = node.asText("").strip();
        return value.isEmpty() ? DEFAULT_NAME : value;
    }

    private static long requiredId(JsonNode node, String field, EventEnvelope event) {
        if (!node.canConvertToLong() || node.asLong() <= 0) {
            throw new PoisonEventException(event.type() + " has no valid " + field);
        }
        return node.asLong();
    }

    private static String bookingCode(JsonNode source, long bookingId) {
        String code = source.path("bookingCode").asText("");
        return code.isBlank() ? "#" + bookingId : code.strip();
    }

    private static String checkoutDetails(JsonNode checkout, EventEnvelope event) {
        var lines = new ArrayList<String>();
        addLine(lines, "Chi nhánh", checkout.path("branchName").asText(""));
        addLine(lines, "Sân", checkout.path("courtName").asText(""));
        LocalDate date = date(checkout.path("date"), event);
        if (date != null) {
            lines.add("Ngày: " + DATE.format(date));
        }
        for (JsonNode slot : checkout.path("slots")) {
            lines.add("Khung giờ: " + time(slot.path("startTime"), event) + " - " + time(slot.path("endTime"), event));
        }
        return String.join("\n", lines);
    }

    private static String bookingSlots(JsonNode booking, EventEnvelope event) {
        var lines = new ArrayList<String>();
        for (JsonNode slot : booking.path("slots")) {
            LocalDate date = date(slot.path("date"), event);
            lines.add("Khung giờ: " + (date == null ? "" : DATE.format(date) + " ")
                    + time(slot.path("startTime"), event) + " - " + time(slot.path("endTime"), event));
        }
        return String.join("\n", lines);
    }

    private static void addLine(List<String> lines, String label, String value) {
        if (!value.isBlank()) {
            lines.add(label + ": " + value.strip());
        }
    }

    private static LocalDate date(JsonNode node, EventEnvelope event) {
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException error) {
            throw new PoisonEventException(event.type() + " has a malformed date");
        }
    }

    private static String time(JsonNode node, EventEnvelope event) {
        try {
            return TIME.format(LocalTime.parse(node.asText()));
        } catch (DateTimeParseException error) {
            throw new PoisonEventException(event.type() + " has a malformed time");
        }
    }

    private static String dateTime(JsonNode node, EventEnvelope event) {
        try {
            return DATE_TIME.format(Instant.parse(node.asText()).atZone(VIETNAM));
        } catch (DateTimeParseException error) {
            throw new PoisonEventException(event.type() + " has a malformed expiresAt");
        }
    }

    private static String money(JsonNode node) {
        if (!node.isNumber()) {
            return "—";
        }
        var format = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        format.setMaximumFractionDigits(0);
        return format.format(node.decimalValue());
    }

    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }
}
