package com.fcourt.notification.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.event.EventEnvelope;
import com.fcourt.notification.event.PoisonEventException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InAppPlannerTest {
    static final ObjectMapper JSON = new ObjectMapper();

    final FakeDirectory directory = new FakeDirectory();
    final InAppPlanner planner = new InAppPlanner(directory);

    static class FakeDirectory implements BranchDirectory {
        Map<Long, Long> owners = Map.of(3L, 70L);
        Map<Long, List<Long>> staff = Map.of(3L, List.of(81L, 82L));
        int ownerCalls;

        @Override
        public OptionalLong ownerOf(long branchId) {
            ownerCalls++;
            Long owner = owners.get(branchId);
            return owner == null ? OptionalLong.empty() : OptionalLong.of(owner);
        }

        @Override
        public List<Long> staffOf(long branchId) {
            return staff.getOrDefault(branchId, List.of());
        }
    }

    @Test
    void confirmedPaymentTellsTheCustomerByUserIdEvenWithoutAnEmail() {
        var plans = planner.plan(event("""
                {"eventId":"%s","type":"PaymentConfirmedEvent",
                 "payment":{"id":9,"bookingId":42,"branchId":3,"customerId":55,
                            "checkout":{"bookingCode":"BK-0042"}}}"""));

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.userId()).isEqualTo(55);
            assertThat(plan.type()).isEqualTo("BOOKING_CONFIRMED");
            assertThat(plan.body()).contains("BK-0042");
            assertThat(plan.link()).isEqualTo("/my-bookings");
            assertThat(plan.dedupeKey()).isEqualTo("booking-confirmed:42");
        });
    }

    @Test
    void guestPaymentWithoutACustomerIdGetsNothing() {
        assertThat(planner.plan(event("""
                {"eventId":"%s","type":"PaymentConfirmedEvent","payment":{"id":9,"bookingId":42,"branchId":3}}""")))
                .isEmpty();
    }

    @Test
    void rejectedPaymentSharesTheCancellationKeyAndKeepsTheReason() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"PaymentRejectedEvent",
                 "payment":{"id":9,"bookingId":42,"branchId":3,"customerId":55,"rejectionReason":"Sai số tiền"}}"""))
                .getFirst();

        assertThat(plan.type()).isEqualTo("PAYMENT_REJECTED");
        assertThat(plan.body()).contains("Sai số tiền").contains("#42");
        assertThat(plan.dedupeKey()).isEqualTo("booking-cancelled:42");
    }

    @Test
    void expiredCheckoutFromPaymentServiceTellsTheCustomer() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"BookingCancelledEvent",
                 "payment":{"id":9,"bookingId":42,"branchId":3,"customerId":55}}""")).getFirst();

        assertThat(plan.userId()).isEqualTo(55);
        assertThat(plan.title()).isEqualTo("Đơn đặt sân đã hết hạn");
        assertThat(directory.ownerCalls).isZero();
    }

    @Test
    void customerCancellationTellsTheCustomerTheOwnerAndTheStaff() {
        var plans = planner.plan(event(cancelled("CUSTOMER", "Bận việc", "55")));

        assertThat(plans).extracting(InAppPlan::userId).containsExactly(55L, 70L, 81L, 82L);
        assertThat(plans.get(0).body()).contains("hoàn lại dự kiến");
        assertThat(plans.get(1).link()).isEqualTo("/admin/bookings");
        assertThat(plans.get(2).link()).isEqualTo("/staff/board");
        assertThat(plans.get(1).body()).contains("Bận việc");
        assertThat(plans).extracting(InAppPlan::dedupeKey).containsOnly("booking-cancelled:42");
    }

    @Test
    void ownerCancellationTellsOnlyTheCustomer() {
        var plans = planner.plan(event(cancelled("ADMIN", "Sân hỏng", "55")));

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.userId()).isEqualTo(55);
            assertThat(plan.title()).isEqualTo("Chủ sân đã hủy đơn đặt sân");
            assertThat(plan.body()).contains("Sân hỏng");
        });
        assertThat(directory.ownerCalls).isZero();
    }

    @Test
    void holdExpiryAndLegacyEventsWithoutARoleDoNotBotherTheOwner() {
        assertThat(planner.plan(event(cancelled("SYSTEM", "Payment hold expired", "55"))))
                .extracting(InAppPlan::userId).containsExactly(55L);
        assertThat(planner.plan(event(cancelled(null, "x", "55")))).extracting(InAppPlan::userId)
                .containsExactly(55L);
        assertThat(directory.ownerCalls).isZero();
    }

    @Test
    void aGuestCancellationStillTellsTheOwner() {
        var plans = planner.plan(event(cancelled("CUSTOMER", "x", "null")));

        assertThat(plans).extracting(InAppPlan::userId).containsExactly(70L, 81L, 82L);
    }

    @Test
    void aBranchWithoutAnOwnerStillTellsTheStaffAndCustomer() {
        directory.owners = Map.of();

        assertThat(planner.plan(event(cancelled("CUSTOMER", "x", "55")))).extracting(InAppPlan::userId)
                .containsExactly(55L, 81L, 82L);
    }

    @Test
    void ownerWhoIsAlsoStaffGetsOneRow() {
        directory.staff = Map.of(3L, List.of(70L, 81L));

        assertThat(planner.plan(event(cancelled("CUSTOMER", "x", "55")))).extracting(InAppPlan::userId)
                .containsExactly(55L, 70L, 81L);
    }

    @Test
    void submittedReceiptWaitsForTheOwnerWithTheEventIdAsKey() {
        String id = UUID.randomUUID().toString();
        var plans = planner.plan(EventEnvelope.parse(JSON, """
                {"eventId":"%s","type":"PaymentSubmittedEvent",
                 "payment":{"id":9,"bookingId":42,"branchId":3,"customerId":55,"checkout":{"bookingCode":"BK-0042"}}}"""
                .formatted(id)));

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.userId()).isEqualTo(70);
            assertThat(plan.type()).isEqualTo("PAYMENT_SUBMITTED");
            assertThat(plan.link()).isEqualTo("/admin/bookings");
            assertThat(plan.dedupeKey()).isEqualTo("payment-submitted:" + id);
        });
    }

    @Test
    void refundCompletedTellsTheCustomer() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"RefundCompletedEvent",
                 "refund":{"id":5,"bookingId":42,"branchId":3,"customerId":55,"amount":120000,"status":"COMPLETED"}}"""))
                .getFirst();

        assertThat(plan.userId()).isEqualTo(55);
        assertThat(plan.body()).contains("#42").contains("120.000");
        assertThat(plan.dedupeKey()).isEqualTo("refund-completed:5");
    }

    @Test
    void refundRequestGoesToTheOwnerOnly() {
        var plans = planner.plan(event("""
                {"eventId":"%s","type":"RefundRequestedEvent",
                 "refund":{"id":5,"bookingId":42,"branchId":3,"customerId":55,"amount":120000}}"""));

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.userId()).isEqualTo(70);
            assertThat(plan.link()).isEqualTo("/admin/refund-requests");
            assertThat(plan.dedupeKey()).isEqualTo("refund-requested:5");
        });
    }

    @Test
    void ownerApprovalTellsTheOwnerWithTheEmailBusinessKey() {
        var plan = planner.plan(event("""
                {"eventId":"%s","type":"OwnerApprovedEvent",
                 "data":{"userId":17,"approvedBy":16,"approvedAt":"2026-11-01T03:00:00Z"}}""")).getFirst();

        assertThat(plan.userId()).isEqualTo(17);
        assertThat(plan.dedupeKey()).isEqualTo("owner-approved:17:2026-11-01T03:00:00Z");
    }

    @Test
    void ownerRegistrationTellsEverySuperAdmin() {
        var plans = planner.plan(event("""
                {"eventId":"%s","type":"OwnerRegisteredEvent",
                 "data":{"userId":17,"fullName":"Chu San","superAdminIds":[1,2,2]}}"""));

        assertThat(plans).extracting(InAppPlan::userId).containsExactly(1L, 2L);
        assertThat(plans.getFirst().link()).isEqualTo("/super-admin/pending");
        assertThat(plans.getFirst().body()).contains("Chu San");
        assertThat(plans.getFirst().dedupeKey()).isEqualTo("owner-registered:17");
    }

    @Test
    void otherEventsAreIgnored() {
        assertThat(planner.plan(event("""
                {"eventId":"%s","type":"PasswordResetRequestedEvent","data":{"email":"a@b.c"}}"""))).isEmpty();
    }

    @Test
    void anEventWithoutABookingIdIsPoison() {
        assertThatThrownBy(() -> planner.plan(event("""
                {"eventId":"%s","type":"PaymentConfirmedEvent","payment":{"customerId":55}}""")))
                .isInstanceOf(PoisonEventException.class);
    }

    @Test
    void aFacilityOutageIsNotSwallowed() {
        var failing = new InAppPlanner(new FakeDirectory() {
            @Override
            public OptionalLong ownerOf(long branchId) {
                throw new IllegalStateException("facility down");
            }
        });

        assertThatThrownBy(() -> failing.plan(event("""
                {"eventId":"%s","type":"PaymentSubmittedEvent","payment":{"id":9,"bookingId":42,"branchId":3}}""")))
                .hasMessage("facility down");
    }

    @Test
    void aLongReasonIsClampedToTheColumnSize() {
        var plans = planner.plan(event(cancelled("ADMIN", "x".repeat(2000), "55")));

        assertThat(plans.getFirst().body()).hasSize(1000).endsWith("...");
    }

    /** The eventId placeholder is filled by {@link #event(String)}. */
    static String cancelled(String role, String reason, String customerId) {
        return "{\"eventId\":\"%s\",\"type\":\"BookingCancelledEvent\",\"booking\":{\"bookingId\":42,\"branchId\":3,"
                + "\"customerId\":" + customerId + ",\"bookingCode\":\"BK-0042\",\"reason\":\"" + reason
                + "\",\"refundAmount\":100000"
                + (role == null ? "" : ",\"cancelledByRole\":\"" + role + "\"") + "}}";
    }

    static EventEnvelope event(String template) {
        return EventEnvelope.parse(JSON, template.formatted(UUID.randomUUID()));
    }
}
