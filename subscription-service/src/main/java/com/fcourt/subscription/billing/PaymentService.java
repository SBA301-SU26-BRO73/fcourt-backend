package com.fcourt.subscription.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fcourt.subscription.billing.BillingDtos.CheckoutRequest;
import com.fcourt.subscription.billing.BillingDtos.PaymentResponse;
import com.fcourt.subscription.billing.PaymentRepository.PaymentRecord;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.payos.PayosClient;
import com.fcourt.subscription.payos.PayosClient.PaymentGatewayException;
import com.fcourt.subscription.payos.PayosClient.PaymentLinkRequest;
import com.fcourt.subscription.payos.PayosProperties;
import com.fcourt.subscription.payos.PayosSignature;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * PayOS checkout for a plan and the webhook that turns a paid checkout into a subscription.
 * The amount is the plan price for the chosen cycle, never a value from the client. The gateway call is
 * made outside any database transaction so a slow gateway does not hold a connection.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PAYOS_SUCCESS = "00";
    private static final int ORDER_CODE_ATTEMPTS = 5;

    private final PaymentRepository payments;
    private final PlanService plans;
    private final SubscriptionService subscriptions;
    private final PayosClient payos;
    private final PayosProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PaymentService(PaymentRepository payments, PlanService plans, SubscriptionService subscriptions,
                   PayosClient payos, PayosProperties properties, TransactionTemplate transaction, Clock clock) {
        this.payments = payments;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payos = payos;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** What happened to a webhook that passed signature and amount checks. */
    public enum WebhookOutcome {
        /** The payment was marked paid and the subscription activated or renewed. */
        ACTIVATED,
        /** A repeated delivery of a payment that was already paid. */
        ALREADY_PROCESSED,
        /** Valid but of no effect: a failure or cancel notice, or a payment that is no longer open. */
        IGNORED
    }

    public PaymentResponse checkout(long adminId, CheckoutRequest request) {
        BillingCycle cycle = BillingCycle.parse(request.billingCycle())
                .orElseThrow(() -> ApiException.badRequest("billingCycle must be MONTHLY or YEARLY"));
        SubscriptionPlanResponse plan = plans.findOnSale(request.planId())
                .orElseThrow(() -> ApiException.notFound("Subscription plan not found"));
        long amount = amountFor(plan, cycle);
        if (!properties.isConfigured()) {
            throw ApiException.unavailable("Payment gateway is not configured");
        }

        Instant now = clock.instant();
        PaymentRecord pending = createPending(adminId, plan, cycle, amount, now);
        try {
            var link = payos.createLink(new PaymentLinkRequest(pending.orderCode(), amount,
                    description(pending.orderCode()), plan.name(), pending.expiredAt().toInstant()));
            transaction.executeWithoutResult(status -> payments.saveLink(pending.id(), link.paymentLinkId(),
                    link.checkoutUrl(), link.qrCode(), clock.instant()));
        } catch (PaymentGatewayException error) {
            log.error("PayOS payment link failed for orderCode={}: {}", pending.orderCode(), error.getMessage());
            transaction.executeWithoutResult(status -> payments.markFailed(pending.id(), clock.instant()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Payment link could not be created");
        }
        return toResponse(payments.findById(pending.id()).orElseThrow());
    }

    /** Owners see their own payments and a super admin sees any; everyone else gets 404, not 403. */
    @Transactional
    public PaymentResponse get(long orderCode, long callerId, boolean superAdmin) {
        var payment = payments.findByOrderCode(orderCode)
                .filter(found -> superAdmin || found.adminId() == callerId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Instant now = clock.instant();
        if (PaymentRepository.PENDING.equals(payment.status()) && !payment.expiredAt().toInstant().isAfter(now)
                && payments.markExpiredIfDue(orderCode, now)) {
            payment = payments.findByOrderCode(orderCode).orElseThrow();
        }
        return toResponse(payment);
    }

    /**
     * Handles a PayOS webhook. Throws {@link WebhookRejectedException} for payloads that must not be trusted
     * (bad signature, unknown order code, amount mismatch); any other exception is a server fault, so the
     * caller answers 5xx and PayOS retries. A verified success is honoured even when our own checkout timer
     * has run out, because PayOS only reports code 00 after the money arrived.
     */
    @Transactional
    public WebhookOutcome handleWebhook(JsonNode payload) {
        JsonNode data = payload == null ? null : payload.get("data");
        String signature = text(payload, "signature");
        String checksumKey = properties.checksumKey();
        if (checksumKey == null || checksumKey.isBlank()) {
            throw new WebhookRejectedException("PayOS checksum key is not configured");
        }
        if (!PayosSignature.verifyWebhook(checksumKey, data, signature)) {
            throw new WebhookRejectedException("Invalid PayOS webhook signature");
        }
        if (!PAYOS_SUCCESS.equals(text(data, "code"))) {
            return WebhookOutcome.IGNORED;
        }
        long orderCode = data.path("orderCode").asLong(0);
        var payment = payments.findByOrderCode(orderCode)
                .orElseThrow(() -> new WebhookRejectedException("Unknown PayOS order code"));
        if (PaymentRepository.PAID.equals(payment.status())) {
            return WebhookOutcome.ALREADY_PROCESSED;
        }
        JsonNode amount = data.path("amount");
        if (!amount.canConvertToLong() || amount.asLong() != payment.amount()) {
            throw new WebhookRejectedException("PayOS amount does not match the payment");
        }
        if (!payments.markPaid(orderCode, text(data, "reference"), clock.instant(), payload.toString())) {
            // Either a concurrent delivery got there first or the payment is FAILED or CANCELLED.
            var current = payments.findByOrderCode(orderCode).orElseThrow();
            return PaymentRepository.PAID.equals(current.status())
                    ? WebhookOutcome.ALREADY_PROCESSED : WebhookOutcome.IGNORED;
        }
        var cycle = BillingCycle.parse(payment.billingCycle())
                .orElseThrow(() -> new IllegalStateException("Stored billing cycle is invalid"));
        subscriptions.activate(payment.adminId(), plans.describe(payment.planId()), cycle);
        return WebhookOutcome.ACTIVATED;
    }

    private PaymentRecord createPending(long adminId, SubscriptionPlanResponse plan, BillingCycle cycle,
                                        long amount, Instant now) {
        Instant expiresAt = now.plus(properties.linkTtl());
        for (int attempt = 0; attempt < ORDER_CODE_ATTEMPTS; attempt++) {
            // Epoch milliseconds stay far below PayOS' limit (2^53 - 1); a clash just tries the next value.
            long orderCode = now.toEpochMilli() + attempt;
            try {
                long id = transaction.execute(status -> payments.insertPending(orderCode, adminId, plan.id(),
                        plan.name(), cycle.name(), amount, expiresAt, now));
                return payments.findById(id).orElseThrow();
            } catch (DuplicateKeyException taken) {
                log.debug("Order code {} already used, trying the next one", orderCode);
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate an order code, try again");
    }

    /** The sandbox override only replaces the price; a cycle the plan is not sold with stays refused. */
    private long amountFor(SubscriptionPlanResponse plan, BillingCycle cycle) {
        Long price = cycle == BillingCycle.YEARLY ? plan.yearlyPrice() : plan.monthlyPrice();
        if (price == null || price <= 0) {
            throw ApiException.badRequest("This plan is not sold with the " + cycle + " billing cycle");
        }
        Long override = properties.amountOverride();
        return override != null && override > 0 ? override : price;
    }

    /** PayOS limits the bank transfer description to 25 characters. */
    static String description(long orderCode) {
        return "SUB" + "%06d".formatted(orderCode % 1_000_000);
    }

    private PaymentResponse toResponse(PaymentRecord payment) {
        boolean open = PaymentRepository.PENDING.equals(payment.status());
        return new PaymentResponse(payment.id(), payment.orderCode(), payment.planId(), payment.planName(),
                payment.billingCycle(), payment.amount(), payment.status(),
                open ? payment.checkoutUrl() : null, open ? payment.qrCode() : null,
                payment.expiredAt(), payment.paidAt(), PaymentRepository.PAID.equals(payment.status()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
