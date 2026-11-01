package com.fcourt.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EmailPlan.Ignore;
import com.fcourt.notification.event.EmailPlan.NoRecipient;
import com.fcourt.notification.event.EmailPlan.Send;
import com.fcourt.notification.event.EmailPlan.Expired;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailPlannerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RESET_URL = "https://fcourt.example/reset-password";

    // Before the 10:30Z expiry the reset events below carry.
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    EmailPlanner planner = new EmailPlanner(RESET_URL, CLOCK);

    @Test
    void ownerApprovalMailsTheOwnerOnce() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"OwnerApprovedEvent","version":1,
                 "data":{"userId":17,"approvedBy":16,"approvedAt":"2026-11-01T03:00:00Z",
                         "email":"owner@demo.com","fullName":"Chu San"}}"""));

        var send = (Send) plan;
        assertThat(send.email().to()).isEqualTo("owner@demo.com");
        assertThat(send.email().subject()).isEqualTo("Hồ sơ chủ sân FCOURT đã được duyệt");
        assertThat(send.email().template()).isEqualTo("owner-approved");
        assertThat(send.email().model()).containsEntry("name", "Chu San");
        assertThat(send.businessKey()).isEqualTo("owner-approved:17");
    }

    @Test
    void ownerApprovalPublishedBeforeTheRecipientWasAddedIsRecordedAsSkipped() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"OwnerApprovedEvent","version":1,
                 "data":{"userId":17,"approvedBy":16,"approvedAt":"2026-11-01T03:00:00Z"}}"""));

        assertThat(plan).isEqualTo(new NoRecipient("owner-approved:17"));
    }

    @Test
    void aMissingNameFallsBackToAPoliteDefault() {
        var send = (Send) planner.plan(event("""
                {"eventId":"%s","type":"OwnerApprovedEvent","version":1,
                 "data":{"userId":17,"email":"owner@demo.com","fullName":"  "}}"""));

        assertThat(send.email().model()).containsEntry("name", "Quý khách");
    }

    @Test
    void passwordResetLinksTheTokenAndShowsTheExpiryInVietnamTime() {
        var send = (Send) planner.plan(event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","version":1,
                 "data":{"userId":7,"email":"customer@demo.com","fullName":"An Nguyen",
                         "token":"abc-DEF_123","expiresAt":"2026-11-01T10:30:00Z"}}"""));

        assertThat(send.email().to()).isEqualTo("customer@demo.com");
        assertThat(send.email().subject()).isEqualTo("Đặt lại mật khẩu FCOURT");
        assertThat(send.email().template()).isEqualTo("password-reset");
        assertThat(send.email().model()).containsEntry("resetLink", RESET_URL + "?token=abc-DEF_123")
                .containsEntry("expiresAt", "17:30 01/11/2026");
        assertThat(send.businessKey()).isNull();
    }

    @Test
    void passwordResetAppendsToAnExistingQueryAndEncodesTheToken() {
        var send = (Send) new EmailPlanner("app://reset?lang=vi", CLOCK).plan(event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","version":1,
                 "data":{"userId":7,"email":"customer@demo.com","token":"a b&c","expiresAt":"2026-11-01T10:30:00Z"}}"""));

        assertThat(send.email().model()).containsEntry("resetLink", "app://reset?lang=vi&token=a+b%26c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void passwordResetWithoutATokenIsPoison(String token) {
        var event = event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","version":1,
                 "data":{"userId":7,"email":"customer@demo.com","token":"TOKEN","expiresAt":"2026-11-01T10:30:00Z"}}"""
                .replace("TOKEN", token));

        assertThatThrownBy(() -> planner.plan(event)).isInstanceOf(PoisonEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01T10:00:00Z", "2026-11-01T09:59:59Z", "2026-10-01T00:00:00Z"})
    void aPasswordResetConsumedAfterItsLinkExpiredIsNotMailed(String expiresAt) {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","version":1,
                 "data":{"userId":7,"email":"customer@demo.com","token":"t","expiresAt":"EXPIRES"}}"""
                .replace("EXPIRES", expiresAt)));

        assertThat(plan).isEqualTo(new Expired());
    }

    @Test
    void passwordResetWithAMalformedExpiryIsPoison() {
        var event = event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","version":1,
                 "data":{"userId":7,"email":"customer@demo.com","token":"t","expiresAt":"tomorrow"}}""");

        assertThatThrownBy(() -> planner.plan(event)).isInstanceOf(PoisonEventException.class);
    }

    @Test
    void confirmedPaymentMailsTheBookingSummaryAndTicketCode() {
        var send = (Send) planner.plan(event(confirmedPayment("customer@demo.com")));

        assertThat(send.email().to()).isEqualTo("customer@demo.com");
        assertThat(send.email().subject()).isEqualTo("Xác nhận đặt sân BK-0042");
        assertThat(send.email().template()).isEqualTo("booking-confirmed");
        var model = send.email().model();
        assertThat(model).containsEntry("name", "An Nguyen").containsEntry("bookingCode", "BK-0042")
                .containsEntry("ticketCode", "12345678901234567890123456789012");
        assertThat(model.get("amount")).contains("200.000");
        assertThat(model.get("details")).isEqualTo("""
                Chi nhánh: FCOURT Quận 1
                Sân: Sân 3
                Ngày: 05/11/2026
                Khung giờ: 18:00 - 18:30
                Khung giờ: 18:30 - 19:00""");
        assertThat(send.businessKey()).isEqualTo("booking-confirmed:42");
    }

    @Test
    void theRecipientNameOverridesTheCheckoutName() {
        var send = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("\"recipient\":{", "\"recipient\":{\"fullName\":\"Tran B\",")));

        assertThat(send.email().model()).containsEntry("name", "Tran B");
    }

    @Test
    void bookingEmailsWithoutARecipientAreRecordedAsSkippedNotSent() {
        var plan = planner.plan(event(confirmedPayment("customer@demo.com").replace(
                "\"recipient\":{\"email\":\"customer@demo.com\"}", "\"recipient\":null")));

        assertThat(plan).isEqualTo(new NoRecipient("booking-confirmed:42"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-address", "a@b.com\\nBcc: victim@x.com", "a@b.com, c@d.com", "<a@b.com>", "",
            "a\\\"b@c.com", "a(b@c.com"})
    void anythingButOneWellFormedAddressIsRefusedWhichBlocksHeaderInjection(String address) {
        var plan = planner.plan(event(confirmedPayment(address)));

        assertThat(plan).isEqualTo(new NoRecipient("booking-confirmed:42"));
    }

    @Test
    void aPaymentEventWithoutABookingIdIsPoison() {
        var event = event(confirmedPayment("customer@demo.com").replace("\"bookingId\":42,", ""));

        assertThatThrownBy(() -> planner.plan(event)).isInstanceOf(PoisonEventException.class);
    }

    @Test
    void aMalformedSlotTimeIsPoison() {
        var event = event(confirmedPayment("customer@demo.com").replace("18:00:00", "six o'clock"));

        assertThatThrownBy(() -> planner.plan(event)).isInstanceOf(PoisonEventException.class);
    }

    @Test
    void aRejectedPaymentIsReportedAsACancellationWithTheReason() {
        var send = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("PaymentConfirmedEvent", "PaymentRejectedEvent")
                .replace("\"checkInCode\"", "\"rejectionReason\":\"Sai nội dung chuyển khoản\",\"checkInCode\"")));

        assertThat(send.email().template()).isEqualTo("booking-cancelled");
        assertThat(send.email().subject()).isEqualTo("Đơn đặt sân BK-0042 đã bị hủy");
        assertThat(send.email().model()).containsEntry("reason", "Thanh toán bị từ chối: Sai nội dung chuyển khoản");
        assertThat(send.businessKey()).isEqualTo("booking-cancelled:42");
    }

    @Test
    void aCustomerCancellationFromBookingServiceShowsReasonSlotsAndRefund() {
        var send = (Send) planner.plan(event(customerCancellation(150000)));

        assertThat(send.email().to()).isEqualTo("customer@demo.com");
        var model = send.email().model();
        assertThat(model).containsEntry("bookingCode", "BK-0042").containsEntry("reason", "Bận việc đột xuất");
        assertThat(model.get("details")).isEqualTo("Khung giờ: 05/11/2026 18:00 - 18:30");
        assertThat(model.get("note")).startsWith("Số tiền hoàn lại dự kiến:").contains("150.000");
        assertThat(send.businessKey()).isEqualTo("booking-cancelled:42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    void aCustomerCancellationWithoutAReasonGetsTheGenericOne(String reason) {
        var send = (Send) planner.plan(event(customerCancellation(0)
                .replace("\"Bận việc đột xuất\"", reason)));

        assertThat(send.email().model()).containsEntry("reason", "Đơn đặt sân đã được hủy");
    }

    @Test
    void noRefundNoteWhenNothingIsRefunded() {
        var send = (Send) planner.plan(event(customerCancellation(0)));

        assertThat(send.email().model()).containsEntry("note", "");
    }

    @Test
    void anUnpaidCheckoutExpiryFromPaymentServiceIsAlsoACancellation() {
        var send = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("PaymentConfirmedEvent", "BookingCancelledEvent")));

        assertThat(send.email().template()).isEqualTo("booking-cancelled");
        assertThat(send.email().model()).containsEntry("reason", "Đơn đặt sân đã hết hạn thanh toán");
    }

    @Test
    void everyWayOfCancellingTheSameBookingSharesOneBusinessKeySoItIsMailedOnce() {
        var byCustomer = (Send) planner.plan(event(customerCancellation(0)));
        var byExpiry = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("PaymentConfirmedEvent", "BookingCancelledEvent")));
        var byRejection = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("PaymentConfirmedEvent", "PaymentRejectedEvent")));

        assertThat(byCustomer.businessKey()).isEqualTo(byExpiry.businessKey()).isEqualTo(byRejection.businessKey());
    }

    @Test
    void aBookingCodeCannotInjectLineBreaksIntoTheSubject() {
        var send = (Send) planner.plan(event(confirmedPayment("customer@demo.com")
                .replace("BK-0042", "BK\\r\\nBcc: victim@x.com")));

        assertThat(send.email().subject()).doesNotContain("\n", "\r");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PaymentSubmittedEvent", "RefundCompletedEvent", "SomethingElseEvent"})
    void otherEventTypesAreIgnored(String type) {
        assertThat(planner.plan(event("{\"eventId\":\"%s\",\"type\":\"" + type + "\"}"))).isInstanceOf(Ignore.class);
    }

    static String confirmedPayment(String email) {
        return """
                {"eventId":"%s","type":"PaymentConfirmedEvent",
                 "recipient":{"email":"EMAIL"},
                 "payment":{"id":9,"bookingId":42,"branchId":3,"amount":200000,"status":"CONFIRMED",
                            "checkInCode":"12345678901234567890123456789012",
                            "checkout":{"bookingId":42,"bookingCode":"BK-0042","branchName":"FCOURT Quận 1",
                                        "courtName":"Sân 3","date":"2026-11-05","customerName":"An Nguyen",
                                        "slots":[{"startTime":"18:00:00","endTime":"18:30:00","price":100000},
                                                 {"startTime":"18:30:00","endTime":"19:00:00","price":100000}]}}}"""
                .replace("EMAIL", email);
    }

    static String customerCancellation(long refund) {
        return """
                {"eventId":"%s","type":"BookingCancelledEvent",
                 "recipient":{"email":"customer@demo.com","fullName":"An Nguyen"},
                 "booking":{"id":42,"bookingId":42,"branchId":3,"customerId":7,"bookingCode":"BK-0042",
                            "status":"CANCELLED","reason":"Bận việc đột xuất","refundAmount":REFUND,
                            "slots":[{"courtId":5,"date":"2026-11-05","startTime":"18:00:00","endTime":"18:30:00"}]}}"""
                .replace("REFUND", Long.toString(refund));
    }

    static EventEnvelope event(String template) {
        return EventEnvelope.parse(JSON, template.formatted(UUID.randomUUID()));
    }
}
