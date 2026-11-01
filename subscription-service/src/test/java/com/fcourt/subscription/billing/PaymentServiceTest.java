package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fcourt.subscription.billing.BillingDtos.CheckoutRequest;
import com.fcourt.subscription.billing.PaymentRepository.PaymentRecord;
import com.fcourt.subscription.billing.PaymentService.WebhookOutcome;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.payos.PayosClient;
import com.fcourt.subscription.payos.PayosClient.PaymentGatewayException;
import com.fcourt.subscription.payos.PayosClient.PaymentLink;
import com.fcourt.subscription.payos.PayosClient.PaymentLinkRequest;
import com.fcourt.subscription.payos.PayosProperties;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    static final String KEY = "checksum-secret";
    static final long ADMIN = 7;
    static final long ORDER = 1_793_527_200_000L;

    PaymentRepository payments;
    PlanService plans;
    SubscriptionService subscriptions;
    PayosClient payos;
    PayosProperties properties;
    PaymentService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        plans = mock(PlanService.class);
        subscriptions = mock(SubscriptionService.class);
        payos = mock(PayosClient.class);
        properties = properties(null);
        service = newService(properties);
        when(plans.findOnSale(2)).thenReturn(Optional.of(plan(2, "Pro", 299_000L, 2_990_000L)));
        when(plans.describe(2)).thenReturn(plan(2, "Pro", 299_000L, 2_990_000L));
    }

    // ---- checkout ----

    @Test
    void checkoutChargesThePlanPriceOfTheCycleAndStoresTheLink() {
        stubInsert(ORDER, 11);
        when(payos.createLink(any())).thenReturn(new PaymentLink("link-1", "https://pay.payos.vn/web/link-1", "qr"));
        when(payments.findById(11)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, "https://pay.payos.vn/web/link-1", "qr")));

        var response = service.checkout(ADMIN, new CheckoutRequest(2L, "monthly"));

        verify(payments).insertPending(ORDER, ADMIN, 2, "Pro", "MONTHLY", 299_000L, NOW.plus(Duration.ofMinutes(15)), NOW);
        var link = ArgumentCaptor.forClass(PaymentLinkRequest.class);
        verify(payos).createLink(link.capture());
        assertThat(link.getValue().orderCode()).isEqualTo(ORDER);
        assertThat(link.getValue().amount()).isEqualTo(299_000);
        assertThat(link.getValue().description()).isEqualTo("SUB" + "%06d".formatted(ORDER % 1_000_000)).hasSizeLessThanOrEqualTo(25);
        assertThat(link.getValue().itemName()).isEqualTo("Pro");
        assertThat(link.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        verify(payments).saveLink(11, "link-1", "https://pay.payos.vn/web/link-1", "qr", NOW);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/link-1");
        assertThat(response.amount()).isEqualTo(299_000);
        assertThat(response.subscriptionActive()).isFalse();
    }

    @Test
    void aYearlyCheckoutChargesTheYearlyPrice() {
        stubInsert(ORDER, 11);
        when(payos.createLink(any())).thenReturn(new PaymentLink("l", "https://pay", null));
        when(payments.findById(11)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 2_990_000, "https://pay", null)));

        service.checkout(ADMIN, new CheckoutRequest(2L, "YEARLY"));

        verify(payments).insertPending(eq(ORDER), eq(ADMIN), eq(2L), eq("Pro"), eq("YEARLY"), eq(2_990_000L), any(), any());
    }

    @Test
    void theAmountOverrideReplacesThePlanPriceForSandboxRuns() {
        service = newService(properties(10_000L));
        stubInsert(ORDER, 11);
        when(payos.createLink(any())).thenReturn(new PaymentLink("l", "https://pay", null));
        when(payments.findById(11)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 10_000, "https://pay", null)));

        service.checkout(ADMIN, new CheckoutRequest(2L, "MONTHLY"));

        verify(payments).insertPending(eq(ORDER), eq(ADMIN), eq(2L), eq("Pro"), eq("MONTHLY"), eq(10_000L), any(), any());
    }

    @Test
    void theAmountOverrideDoesNotSellACycleThePlanHasNoPriceFor() {
        service = newService(properties(10_000L));
        when(plans.findOnSale(3)).thenReturn(Optional.of(plan(3, "Monthly only", 100_000L, null)));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(3L, "YEARLY")))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST,
                        "This plan is not sold with the YEARLY billing cycle"));
        verifyNoInteractions(payments, payos);
    }

    @Test
    void anUnknownBillingCycleIsABadRequest() {
        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(2L, "weekly")))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST, "billingCycle must be MONTHLY or YEARLY"));
        verifyNoInteractions(payments, payos);
    }

    @Test
    void aPlanThatIsMissingOrNotOnSaleIsNotFound() {
        when(plans.findOnSale(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(9L, "MONTHLY")))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Subscription plan not found"));
        verifyNoInteractions(payments, payos);
    }

    @Test
    void aPlanWithoutAPriceForTheCycleCannotBeBought() {
        when(plans.findOnSale(3)).thenReturn(Optional.of(plan(3, "Monthly only", 100_000L, null)));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(3L, "YEARLY")))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST,
                        "This plan is not sold with the YEARLY billing cycle"));
        verifyNoInteractions(payments, payos);
    }

    @Test
    void aFreePlanCannotBeCheckedOut() {
        when(plans.findOnSale(4)).thenReturn(Optional.of(plan(4, "Free", 0L, 0L)));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(4L, "MONTHLY")))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_REQUEST,
                        "This plan is not sold with the MONTHLY billing cycle"));
    }

    @Test
    void withoutGatewayCredentialsCheckoutIsUnavailableAndNothingIsStored() {
        service = newService(new PayosProperties("", "", "", "https://api-merchant.payos.vn", "", "",
                Duration.ofMinutes(15), null));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(2L, "MONTHLY")))
                .satisfies(error -> assertApi(error, HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway is not configured"));
        verifyNoInteractions(payments, payos);
    }

    @Test
    void aGatewayFailureMarksThePaymentFailedAndAnswersBadGateway() {
        stubInsert(ORDER, 11);
        when(payos.createLink(any())).thenThrow(new PaymentGatewayException("PayOS request failed: ResourceAccessException"));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(2L, "MONTHLY")))
                .satisfies(error -> assertApi(error, HttpStatus.BAD_GATEWAY, "Payment link could not be created"));

        verify(payments).markFailed(11, NOW);
        verify(payments, never()).saveLink(anyLong(), any(), any(), any(), any());
    }

    @Test
    void anOrderCodeClashTriesTheNextCode() {
        when(payments.insertPending(eq(ORDER), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenThrow(new DuplicateKeyException("subscription_payments_order_code_key"));
        when(payments.insertPending(eq(ORDER + 1), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(12L);
        when(payos.createLink(any())).thenReturn(new PaymentLink("l", "https://pay", null));
        when(payments.findById(12)).thenReturn(Optional.of(new PaymentRecord(12, ORDER + 1, ADMIN, 2, "Pro", "MONTHLY",
                299_000, PaymentRepository.PENDING, "https://pay", null, NOW.plus(Duration.ofMinutes(15)).atOffset(ZoneOffset.UTC), null)));

        service.checkout(ADMIN, new CheckoutRequest(2L, "MONTHLY"));

        var link = ArgumentCaptor.forClass(PaymentLinkRequest.class);
        verify(payos).createLink(link.capture());
        assertThat(link.getValue().orderCode()).isEqualTo(ORDER + 1);
    }

    @Test
    void givingUpAfterRepeatedOrderCodeClashesIsServiceUnavailable() {
        when(payments.insertPending(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenThrow(new DuplicateKeyException("subscription_payments_order_code_key"));

        assertThatThrownBy(() -> service.checkout(ADMIN, new CheckoutRequest(2L, "MONTHLY")))
                .satisfies(error -> assertApi(error, HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not allocate an order code, try again"));
        verify(payments, times(5)).insertPending(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any());
        verifyNoInteractions(payos);
    }

    // ---- reading a payment ----

    @Test
    void anOwnerReadsTheirOwnPendingPaymentWithItsPaymentLink() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, "https://pay", "qr")));

        var response = service.get(ORDER, ADMIN, false);

        assertThat(response.checkoutUrl()).isEqualTo("https://pay");
        assertThat(response.qrCode()).isEqualTo("qr");
    }

    @Test
    void anotherOwnerGetsNotFoundButASuperAdminCanReadAnyPayment() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, "https://pay", "qr")));

        assertThatThrownBy(() -> service.get(ORDER, 99, false))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Payment not found"));
        assertThat(service.get(ORDER, 99, true).orderCode()).isEqualTo(ORDER);
    }

    @Test
    void anUnknownOrderCodeIsNotFound() {
        when(payments.findByOrderCode(5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(5, ADMIN, false))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Payment not found"));
    }

    @Test
    void aPendingPaymentPastItsDeadlineIsReportedExpiredWithoutALink() {
        var overdue = record(PaymentRepository.PENDING, 299_000, "https://pay", "qr", NOW.minusSeconds(1));
        var expired = record(PaymentRepository.EXPIRED, 299_000, "https://pay", "qr", NOW.minusSeconds(1));
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(overdue), Optional.of(expired));
        when(payments.markExpiredIfDue(ORDER, NOW)).thenReturn(true);

        var response = service.get(ORDER, ADMIN, false);

        assertThat(response.status()).isEqualTo("EXPIRED");
        assertThat(response.checkoutUrl()).isNull();
        assertThat(response.qrCode()).isNull();
    }

    @Test
    void aPaidPaymentIsReportedAsActivatedWithoutALink() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PAID, 299_000, "https://pay", "qr")));

        var response = service.get(ORDER, ADMIN, false);

        assertThat(response.subscriptionActive()).isTrue();
        assertThat(response.checkoutUrl()).isNull();
        verify(payments, never()).markExpiredIfDue(anyLong(), any());
    }

    // ---- webhook ----

    @Test
    void aVerifiedPaymentIsMarkedPaidAndActivatesTheSubscriptionOnce() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, "https://pay", "qr")));
        when(payments.markPaid(eq(ORDER), eq("FT" + ORDER), eq(NOW), anyString())).thenReturn(true);
        var payload = WebhookFixtures.paid(KEY, ORDER, 299_000);

        var outcome = service.handleWebhook(payload);

        assertThat(outcome).isEqualTo(WebhookOutcome.ACTIVATED);
        verify(payments).markPaid(ORDER, "FT" + ORDER, NOW, payload.toString());
        verify(subscriptions).activate(eq(ADMIN), eq(plan(2, "Pro", 299_000L, 2_990_000L)), eq(BillingCycle.MONTHLY));
    }

    @Test
    void aYearlyPaymentActivatesAYearlySubscription() {
        var yearly = new PaymentRecord(11, ORDER, ADMIN, 2, "Pro", "YEARLY", 2_990_000, PaymentRepository.PENDING, null, null,
                NOW.plusSeconds(600).atOffset(ZoneOffset.UTC), null);
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(yearly));
        when(payments.markPaid(anyLong(), any(), any(), anyString())).thenReturn(true);

        service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 2_990_000));

        verify(subscriptions).activate(eq(ADMIN), any(), eq(BillingCycle.YEARLY));
    }

    @Test
    void aRepeatedDeliveryOfAPaidPaymentDoesNothing() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PAID, 299_000, null, null)));

        assertThat(service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000)))
                .isEqualTo(WebhookOutcome.ALREADY_PROCESSED);

        verify(payments, never()).markPaid(anyLong(), any(), any(), anyString());
        verifyNoInteractions(subscriptions);
    }

    @Test
    void whenAConcurrentDeliveryWinsTheRaceThisOneDoesNotActivateAgain() {
        when(payments.findByOrderCode(ORDER)).thenReturn(
                Optional.of(record(PaymentRepository.PENDING, 299_000, null, null)),
                Optional.of(record(PaymentRepository.PAID, 299_000, null, null)));
        when(payments.markPaid(anyLong(), any(), any(), anyString())).thenReturn(false);

        assertThat(service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000)))
                .isEqualTo(WebhookOutcome.ALREADY_PROCESSED);
        verifyNoInteractions(subscriptions);
    }

    @Test
    void aPaymentThatCannotBePaidAnymoreIsIgnored() {
        when(payments.findByOrderCode(ORDER)).thenReturn(
                Optional.of(record(PaymentRepository.FAILED, 299_000, null, null)),
                Optional.of(record(PaymentRepository.FAILED, 299_000, null, null)));
        when(payments.markPaid(anyLong(), any(), any(), anyString())).thenReturn(false);

        assertThat(service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000))).isEqualTo(WebhookOutcome.IGNORED);
        verifyNoInteractions(subscriptions);
    }

    @Test
    void aVerifiedPaymentIsHonouredEvenAfterOurOwnTimerExpired() {
        var expired = record(PaymentRepository.EXPIRED, 299_000, null, null, NOW.minusSeconds(60));
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(expired));
        when(payments.markPaid(anyLong(), any(), any(), anyString())).thenReturn(true);

        assertThat(service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000))).isEqualTo(WebhookOutcome.ACTIVATED);
    }

    @Test
    void aBadSignatureIsRejectedBeforeTheDatabaseIsTouched() {
        var forged = WebhookFixtures.paid("not-the-checksum-key", ORDER, 299_000);

        assertThatThrownBy(() -> service.handleWebhook(forged))
                .isInstanceOf(WebhookRejectedException.class).hasMessage("Invalid PayOS webhook signature");
        verifyNoInteractions(payments, subscriptions);
    }

    @Test
    void aTamperedAmountInvalidatesTheSignature() {
        ObjectNode data = WebhookFixtures.successData(ORDER, 299_000);
        var payload = WebhookFixtures.signed(KEY, data);
        ((ObjectNode) payload.get("data")).put("amount", 1);

        assertThatThrownBy(() -> service.handleWebhook(payload)).isInstanceOf(WebhookRejectedException.class);
        verifyNoInteractions(payments, subscriptions);
    }

    @Test
    void aPayloadWithoutDataOrSignatureIsRejected() throws Exception {
        assertThatThrownBy(() -> service.handleWebhook(WebhookFixtures.JSON.readTree("{}")))
                .isInstanceOf(WebhookRejectedException.class);
        assertThatThrownBy(() -> service.handleWebhook(null)).isInstanceOf(WebhookRejectedException.class);
        assertThatThrownBy(() -> service.handleWebhook(WebhookFixtures.JSON.readTree("{\"data\":{\"orderCode\":1}}")))
                .isInstanceOf(WebhookRejectedException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void withoutAConfiguredKeyNothingVerifies() {
        service = newService(new PayosProperties("", "", null, "https://api-merchant.payos.vn", "", "",
                Duration.ofMinutes(15), null));

        assertThatThrownBy(() -> service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000)))
                .isInstanceOf(WebhookRejectedException.class).hasMessage("PayOS checksum key is not configured");
        verifyNoInteractions(payments, subscriptions);
    }

    @Test
    void aSignedPaymentForAnUnknownOrderCodeIsRejected() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 299_000)))
                .isInstanceOf(WebhookRejectedException.class).hasMessage("Unknown PayOS order code");
    }

    @Test
    void anAmountThatDiffersFromTheCheckoutIsRejectedAndNothingIsActivated() {
        when(payments.findByOrderCode(ORDER)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, null, null)));

        assertThatThrownBy(() -> service.handleWebhook(WebhookFixtures.paid(KEY, ORDER, 1_000)))
                .isInstanceOf(WebhookRejectedException.class).hasMessage("PayOS amount does not match the payment");
        verify(payments, never()).markPaid(anyLong(), any(), any(), anyString());
        verifyNoInteractions(subscriptions);
    }

    @Test
    void aNonSuccessCodeIsIgnoredWithoutLookingUpThePayment() {
        ObjectNode data = WebhookFixtures.successData(ORDER, 299_000);
        data.put("code", "01");

        assertThat(service.handleWebhook(WebhookFixtures.signed(KEY, data))).isEqualTo(WebhookOutcome.IGNORED);
        verifyNoInteractions(payments, subscriptions);
    }

    // ---- helpers ----

    PaymentService newService(PayosProperties props) {
        var transaction = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                TransactionStatus status = new SimpleTransactionStatus();
                return action.doInTransaction(status);
            }
        };
        return new PaymentService(payments, plans, subscriptions, payos, props, transaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    void stubInsert(long orderCode, long paymentId) {
        when(payments.insertPending(eq(orderCode), anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(paymentId);
        when(payments.findById(paymentId)).thenReturn(Optional.of(record(PaymentRepository.PENDING, 299_000, null, null)));
    }

    static PayosProperties properties(Long override) {
        return new PayosProperties("client", "api-key", KEY, "https://api-merchant.payos.vn", "https://app/return",
                "https://app/cancel", Duration.ofMinutes(15), override);
    }

    static void assertApi(Throwable error, HttpStatus status, String detail) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.status()).isEqualTo(status);
            assertThat(api.getMessage()).isEqualTo(detail);
        });
    }

    static PaymentRecord record(String status, long amount, String checkoutUrl, String qrCode) {
        return record(status, amount, checkoutUrl, qrCode, NOW.plus(Duration.ofMinutes(15)));
    }

    static PaymentRecord record(String status, long amount, String checkoutUrl, String qrCode, Instant expiredAt) {
        return new PaymentRecord(11, ORDER, ADMIN, 2, "Pro", "MONTHLY", amount, status, checkoutUrl, qrCode,
                expiredAt.atOffset(ZoneOffset.UTC), PaymentRepository.PAID.equals(status) ? NOW.atOffset(ZoneOffset.UTC) : null);
    }

    static SubscriptionPlanResponse plan(long id, String name, Long monthly, Long yearly) {
        return new SubscriptionPlanResponse(id, name, null, monthly, yearly, 15, 3, true, null, false, List.of(),
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
