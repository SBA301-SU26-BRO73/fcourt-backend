package com.fcourt.subscription.billing;

import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response shapes of the subscription purchase API (see README, subscription-service). */
public final class BillingDtos {
    private BillingDtos() {
    }

    /** {@code billingCycle} is MONTHLY or YEARLY, case-insensitive. */
    public record CheckoutRequest(
            @NotNull(message = "planId is required") Long planId,
            @NotBlank(message = "billingCycle is required") String billingCycle) {
    }

    /**
     * The current (or latest) subscription of a court owner. {@code active} means today is within its dates;
     * {@code starter} marks the free subscription given before any purchase; {@code upcoming} lists paid
     * periods already bought that start after this one, in order.
     */
    public record SubscriptionResponse(long id, long adminId, SubscriptionPlanResponse plan, int maxCourts,
                                       int maxBranches, LocalDate startDate, LocalDate endDate, boolean active,
                                       boolean starter, List<UpcomingSubscription> upcoming) {
    }

    /** A paid period queued behind the current one. */
    public record UpcomingSubscription(long id, SubscriptionPlanResponse plan, int maxCourts, int maxBranches,
                                       LocalDate startDate, LocalDate endDate) {
    }

    /**
     * A PayOS checkout. {@code amount} is in VND. {@code status}: PENDING, PAID, EXPIRED, CANCELLED or
     * FAILED. {@code checkoutUrl} and {@code qrCode} are present while the payment can still be made.
     */
    public record PaymentResponse(long id, long orderCode, long planId, String planName, String billingCycle,
                                  long amount, String status, String checkoutUrl, String qrCode,
                                  OffsetDateTime expiredAt, OffsetDateTime paidAt, boolean subscriptionActive) {
    }

    /** Acknowledgement of a PayOS webhook; it says nothing about whether the payload was accepted. */
    public record WebhookAck(boolean received) {
    }
}
