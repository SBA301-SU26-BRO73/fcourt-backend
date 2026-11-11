package com.fcourt.notification.inapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fcourt.notification.event.BusinessKeys;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.event.PoisonEventException;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns events into in-app notifications. Recipients are resolved by user id and never depend on an email address:
 * <ul>
 *   <li>Customer: {@code customerId} of {@code PaymentConfirmedEvent}, {@code PaymentRejectedEvent},
 *       {@code BookingCancelledEvent} and {@code RefundCompletedEvent}. No id (guest booking) means no notification.</li>
 *   <li>Branch owner (via {@link BranchDirectory}): {@code PaymentSubmittedEvent}, {@code RefundRequestedEvent}
 *       and {@code BookingCancelledEvent} cancelled by the customer ({@code booking.cancelledByRole = CUSTOMER}).
 *       Branch staff get the cancellation too.</li>
 *   <li>Court owner: {@code OwnerApprovedEvent} ({@code data.userId}).</li>
 *   <li>Super admins: {@code OwnerRegisteredEvent} carries them in {@code data.superAdminIds}.</li>
 * </ul>
 */
@Component
public class InAppPlanner {
    private static final Logger log = LoggerFactory.getLogger(InAppPlanner.class);

    static final String CUSTOMER = "CUSTOMER";
    static final String ADMIN = "ADMIN";
    static final String SYSTEM = "SYSTEM";

    private static final String MY_BOOKINGS = "/my-bookings";
    private static final String ADMIN_BOOKINGS = "/admin/bookings";
    private static final String ADMIN_REFUNDS = "/admin/refund-requests";
    private static final String STAFF_BOARD = "/staff/board";
    private static final String ADMIN_HOME = "/admin/dashboard";
    private static final String SUPER_ADMIN_PENDING = "/super-admin/pending";

    private final BranchDirectory directory;

    public InAppPlanner(BranchDirectory directory) {
        this.directory = directory;
    }

    public List<InAppPlan> plan(EventEnvelope event) {
        return switch (event.type()) {
            case "PaymentConfirmedEvent" -> confirmed(event);
            case "PaymentRejectedEvent" -> rejected(event);
            case "BookingCancelledEvent" -> cancelled(event);
            case "RefundCompletedEvent" -> refundCompleted(event);
            case "PaymentSubmittedEvent" -> submitted(event);
            case "RefundRequestedEvent" -> refundRequested(event);
            case "OwnerApprovedEvent" -> ownerApproved(event);
            case "OwnerRegisteredEvent" -> ownerRegistered(event);
            default -> List.of();
        };
    }

    private List<InAppPlan> confirmed(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), event);
        return customer(payment.path("customerId"), "BOOKING_CONFIRMED", "Đặt sân thành công",
                "Đơn đặt sân " + code(payment.path("checkout"), bookingId) + " đã được xác nhận.",
                MY_BOOKINGS, BusinessKeys.bookingConfirmed(bookingId));
    }

    private List<InAppPlan> rejected(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), event);
        String reason = text(payment.path("rejectionReason"));
        return customer(payment.path("customerId"), "PAYMENT_REJECTED", "Thanh toán bị từ chối",
                "Thanh toán cho đơn " + code(payment.path("checkout"), bookingId) + " đã bị từ chối"
                        + (reason.isEmpty() ? "" : ": " + reason) + ". Đơn đặt sân đã bị hủy.",
                MY_BOOKINGS, BusinessKeys.bookingCancelled(bookingId));
    }

    private List<InAppPlan> cancelled(EventEnvelope event) {
        JsonNode booking = event.root().path("booking");
        if (!booking.isObject()) {
            // Published by payment-service when an unpaid checkout expires.
            JsonNode payment = event.root().path("payment");
            long bookingId = requiredId(payment.path("bookingId"), event);
            return customer(payment.path("customerId"), "BOOKING_CANCELLED", "Đơn đặt sân đã hết hạn",
                    "Đơn đặt sân " + code(payment.path("checkout"), bookingId) + " đã bị hủy vì hết hạn thanh toán.",
                    MY_BOOKINGS, BusinessKeys.bookingCancelled(bookingId));
        }
        long bookingId = requiredId(booking.path("bookingId"), event);
        String code = code(booking, bookingId);
        String role = booking.path("cancelledByRole").asText("");
        String reason = text(booking.path("reason"));
        var plans = new ArrayList<InAppPlan>();
        String key = BusinessKeys.bookingCancelled(bookingId);
        switch (role) {
            case ADMIN -> plans.addAll(customer(booking.path("customerId"), "BOOKING_CANCELLED",
                    "Chủ sân đã hủy đơn đặt sân", "Đơn " + code + " đã được chủ sân hủy"
                            + (reason.isEmpty() ? "." : ". Lý do: " + reason)
                            + refundNote(booking), MY_BOOKINGS, key));
            case SYSTEM -> plans.addAll(customer(booking.path("customerId"), "BOOKING_CANCELLED",
                    "Đơn đặt sân đã hết hạn", "Đơn đặt sân " + code + " đã bị hủy vì hết hạn thanh toán.",
                    MY_BOOKINGS, key));
            default -> plans.addAll(customer(booking.path("customerId"), "BOOKING_CANCELLED",
                    "Đã hủy đặt sân", "Đơn đặt sân " + code + " đã được hủy." + refundNote(booking),
                    MY_BOOKINGS, key));
        }
        if (CUSTOMER.equals(role)) {
            // Only an explicit customer cancellation concerns the owner; events from before cancelledByRole
            // existed carry no role and stay silent rather than guess.
            long branchId = optionalId(booking.path("branchId"));
            if (branchId > 0) {
                String title = "Khách đã hủy đặt sân";
                String body = "Đơn " + code + " đã được khách hủy" + (reason.isEmpty() ? "." : ". Lý do: " + reason);
                directory.ownerOf(branchId).ifPresentOrElse(
                        owner -> plans.add(new InAppPlan(owner, "BOOKING_CANCELLED_BY_CUSTOMER", title, body,
                                ADMIN_BOOKINGS, key)),
                        () -> log.warn("Branch {} has no owner; owner not notified of cancellation", branchId));
                for (long staff : directory.staffOf(branchId)) {
                    plans.add(new InAppPlan(staff, "BOOKING_CANCELLED_BY_CUSTOMER", title, body, STAFF_BOARD, key));
                }
            }
        }
        return distinct(plans);
    }

    private List<InAppPlan> refundCompleted(EventEnvelope event) {
        JsonNode refund = event.root().path("refund");
        long refundId = requiredId(refund.path("id"), event);
        long bookingId = requiredId(refund.path("bookingId"), event);
        return customer(refund.path("customerId"), "REFUND_COMPLETED", "Đã hoàn tiền",
                "Khoản hoàn " + money(refund.path("amount")) + " cho đơn #" + bookingId + " đã được chuyển.",
                MY_BOOKINGS, BusinessKeys.refundCompleted(refundId));
    }

    private List<InAppPlan> submitted(EventEnvelope event) {
        JsonNode payment = event.root().path("payment");
        long bookingId = requiredId(payment.path("bookingId"), event);
        return owner(optionalId(payment.path("branchId")), "PAYMENT_SUBMITTED", "Biên lai chờ duyệt",
                "Đơn " + code(payment.path("checkout"), bookingId) + " đã gửi biên lai thanh toán, đang chờ bạn duyệt.",
                ADMIN_BOOKINGS, BusinessKeys.paymentSubmitted(event.eventId()));
    }

    private List<InAppPlan> refundRequested(EventEnvelope event) {
        JsonNode refund = event.root().path("refund");
        long refundId = requiredId(refund.path("id"), event);
        long bookingId = requiredId(refund.path("bookingId"), event);
        return owner(optionalId(refund.path("branchId")), "REFUND_REQUESTED", "Yêu cầu hoàn tiền mới",
                "Đơn #" + bookingId + " có yêu cầu hoàn tiền " + money(refund.path("amount")) + ".",
                ADMIN_REFUNDS, BusinessKeys.refundRequested(refundId));
    }

    private List<InAppPlan> ownerApproved(EventEnvelope event) {
        JsonNode data = event.root().path("data");
        long userId = requiredId(data.path("userId"), event);
        JsonNode approvedAt = data.path("approvedAt");
        // Same key as the email: each approval is its own fact, without approvedAt the eventId stands in.
        String key = approvedAt.isMissingNode() || approvedAt.isNull()
                ? BusinessKeys.ownerApprovedByEvent(userId, event.eventId())
                : BusinessKeys.ownerApproved(userId, instant(approvedAt, event).toString());
        return List.of(new InAppPlan(userId, "OWNER_APPROVED", "Hồ sơ chủ sân đã được duyệt",
                "Tài khoản chủ sân của bạn đã được duyệt. Bạn có thể tạo chi nhánh và sân ngay bây giờ.",
                ADMIN_HOME, key));
    }

    private List<InAppPlan> ownerRegistered(EventEnvelope event) {
        JsonNode data = event.root().path("data");
        long userId = requiredId(data.path("userId"), event);
        String name = text(data.path("fullName"));
        String key = BusinessKeys.ownerRegistered(userId);
        var plans = new ArrayList<InAppPlan>();
        for (JsonNode id : data.path("superAdminIds")) {
            long admin = optionalId(id);
            if (admin > 0) {
                plans.add(new InAppPlan(admin, "OWNER_REGISTERED", "Chủ sân mới chờ duyệt",
                        (name.isEmpty() ? "Một chủ sân" : "Chủ sân " + name) + " vừa đăng ký và đang chờ duyệt hồ sơ.",
                        SUPER_ADMIN_PENDING, key));
            }
        }
        return distinct(plans);
    }

    private List<InAppPlan> customer(JsonNode customerId, String type, String title, String body, String link,
                                     String key) {
        long user = optionalId(customerId);
        return user > 0 ? List.of(new InAppPlan(user, type, title, body, link, key)) : List.of();
    }

    private List<InAppPlan> owner(long branchId, String type, String title, String body, String link, String key) {
        if (branchId <= 0) {
            return List.of();
        }
        var owner = directory.ownerOf(branchId);
        if (owner.isEmpty()) {
            log.warn("Branch {} has no owner; no in-app notification of type {}", branchId, type);
            return List.of();
        }
        return List.of(new InAppPlan(owner.getAsLong(), type, title, body, link, key));
    }

    private static List<InAppPlan> distinct(List<InAppPlan> plans) {
        Set<Long> seen = new LinkedHashSet<>();
        return plans.stream().filter(plan -> seen.add(plan.userId())).toList();
    }

    private static String refundNote(JsonNode booking) {
        JsonNode refund = booking.path("refundAmount");
        return refund.isNumber() && refund.decimalValue().signum() > 0
                ? " Số tiền hoàn lại dự kiến: " + money(refund) + "." : "";
    }

    private static String code(JsonNode source, long bookingId) {
        String code = source.path("bookingCode").asText("").strip();
        return code.isEmpty() ? "#" + bookingId : code;
    }

    private static String text(JsonNode node) {
        return node.asText("").strip().replaceAll("\\s+", " ");
    }

    private static long optionalId(JsonNode node) {
        return node.canConvertToLong() && node.asLong() > 0 ? node.asLong() : -1;
    }

    private static long requiredId(JsonNode node, EventEnvelope event) {
        long id = optionalId(node);
        if (id <= 0) {
            throw new PoisonEventException(event.type() + " has a missing or invalid id");
        }
        return id;
    }

    private static Instant instant(JsonNode node, EventEnvelope event) {
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException error) {
            throw new PoisonEventException(event.type() + " has a malformed timestamp");
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
}
