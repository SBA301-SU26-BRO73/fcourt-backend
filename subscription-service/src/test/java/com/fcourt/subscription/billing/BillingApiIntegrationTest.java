package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.subscription.IntegrationSupport;
import com.fcourt.subscription.payos.PayosClient;
import com.fcourt.subscription.payos.PayosClient.PaymentGatewayException;
import com.fcourt.subscription.payos.PayosClient.PaymentLink;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Subscription purchase end to end: real security chain, Postgres/Flyway and PayOS webhook signatures.
 * Only the outgoing PayOS call is replaced by a stub. Needs Docker.
 *
 * <p>The starter plan follows the cheapest active plan in the shared integration database. Other integration tests
 * can create plans in this same database, so its ID and court limit are data-dependent.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillingApiIntegrationTest extends IntegrationSupport {
    static final String CHECKSUM = "it-checksum-key";
    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final AtomicLong OWNERS = new AtomicLong(1_000);
    static final AtomicLong LINKS = new AtomicLong();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean PayosClient payos;

    @DynamicPropertySource
    static void payosProperties(DynamicPropertyRegistry registry) {
        registry.add("payos.client-id", () -> "it-client");
        registry.add("payos.api-key", () -> "it-api-key");
        registry.add("payos.checksum-key", () -> CHECKSUM);
        registry.add("payos.return-url", () -> "https://app.test/return");
        registry.add("payos.cancel-url", () -> "https://app.test/cancel");
    }

    @BeforeEach
    void stubPayos() {
        when(payos.createLink(any())).thenAnswer(call -> {
            long n = LINKS.incrementAndGet();
            return new PaymentLink("link-" + n, "https://pay.payos.vn/web/link-" + n, "qr-" + n);
        });
    }

    @Test
    void anOwnerWithoutASubscriptionGetsAStarterOnTheCheapestPlanOnlyOnce() throws Exception {
        long owner = OWNERS.incrementAndGet();
        long cheapestPlanId = jdbc.queryForObject("""
                SELECT id FROM subscription_plans WHERE is_active AND deleted_at IS NULL
                ORDER BY monthly_price ASC NULLS LAST, id LIMIT 1
                """, Long.class);
        int cheapestPlanCourts = jdbc.queryForObject("SELECT max_courts FROM subscription_plans WHERE id = ?",
                Integer.class, cheapestPlanId);

        var first = body(mySubscription(owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value(cheapestPlanId))
                .andExpect(jsonPath("$.maxCourts").value(cheapestPlanCourts))
                .andExpect(jsonPath("$.active").value(true)));
        var second = body(mySubscription(owner).andExpect(status().isOk()));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(first.get("endDate").asText()).isEqualTo(today().plusYears(1).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void onlyOwnersAndSuperAdminsMayUseTheSubscriptionRoutesButTheWebhookIsOpen() throws Exception {
        mvc.perform(get("/api/subscriptions/my-subscription")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subscriptions/my-subscription").header("Authorization", bearer(5, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(5, "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(2, "MONTHLY")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/subscriptions/payment-webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));
    }

    @Test
    void checkoutStoresAPendingPaymentAtThePlanPriceAndHidesItFromOtherOwners() throws Exception {
        long owner = OWNERS.incrementAndGet();

        var payment = checkout(owner, 3, "MONTHLY");

        assertThat(payment.get("status").asText()).isEqualTo("PENDING");
        assertThat(payment.get("amount").asLong()).isEqualTo(299_000);
        assertThat(payment.get("checkoutUrl").asText()).startsWith("https://pay.payos.vn/web/link-");
        long orderCode = payment.get("orderCode").asLong();
        assertThat(jdbc.queryForObject("SELECT status || '/' || amount FROM subscription_payments WHERE order_code = ?",
                String.class, orderCode)).isEqualTo("PENDING/299000.00");

        mvc.perform(get("/api/subscriptions/payments/" + orderCode).header("Authorization", bearer(owner, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planName").isNotEmpty());
        mvc.perform(get("/api/subscriptions/payments/" + orderCode)
                        .header("Authorization", bearer(OWNERS.incrementAndGet(), "ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/subscriptions/payments/" + orderCode).header("Authorization", bearer(1, "SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void checkoutRejectsBadInputAndSurvivesAGatewayFailureAsAFailedPayment() throws Exception {
        long owner = OWNERS.incrementAndGet();
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(3, "WEEKLY")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(99_999, "MONTHLY")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        when(payos.createLink(any())).thenThrow(new PaymentGatewayException("PayOS request failed"));
        mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(3, "MONTHLY")))
                .andExpect(status().isBadGateway());

        assertThat(jdbc.queryForObject("SELECT status FROM subscription_payments WHERE admin_id = ?", String.class,
                owner)).isEqualTo("FAILED");
    }

    @Test
    void aPaidWebhookActivatesThePlanReplacingTheStarterAndIsIdempotent() throws Exception {
        long owner = OWNERS.incrementAndGet();
        mySubscription(owner).andExpect(status().isOk());
        var payment = checkout(owner, 2, "MONTHLY");
        long orderCode = payment.get("orderCode").asLong();

        webhook(WebhookFixtures.paid(CHECKSUM, orderCode, 1_499_000)).andExpect(status().isOk());
        webhook(WebhookFixtures.paid(CHECKSUM, orderCode, 1_499_000)).andExpect(status().isOk());

        mvc.perform(get("/api/subscriptions/payments/" + orderCode).header("Authorization", bearer(owner, "ADMIN")))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.subscriptionActive").value(true));
        mySubscription(owner)
                .andExpect(jsonPath("$.plan.id").value(2))
                .andExpect(jsonPath("$.maxCourts").value(99))
                .andExpect(jsonPath("$.startDate").value(today().toString()))
                .andExpect(jsonPath("$.endDate").value(today().plusMonths(1).toString()))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ? AND plan_id = 2",
                Integer.class, owner)).isEqualTo(1);
        // The starter only began today, so it is withdrawn (kept as a deleted row) and exactly one subscription
        // is live; no row ends before it starts.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ? AND end_date >= ? "
                + "AND deleted_at IS NULL", Integer.class, owner, today())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM subscriptions WHERE admin_id = ? "
                + "AND starter", Boolean.class, owner)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ? AND end_date < start_date",
                Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT payos_reference FROM subscription_payments WHERE order_code = ?",
                String.class, orderCode)).isEqualTo("FT" + orderCode);
    }

    @Test
    void payingAgainForTheSamePlanExtendsFromTheCurrentEndDate() throws Exception {
        long owner = OWNERS.incrementAndGet();
        long first = checkout(owner, 2, "MONTHLY").get("orderCode").asLong();
        webhook(WebhookFixtures.paid(CHECKSUM, first, 1_499_000)).andExpect(status().isOk());
        long second = checkout(owner, 2, "YEARLY").get("orderCode").asLong();

        webhook(WebhookFixtures.paid(CHECKSUM, second, 14_990_000)).andExpect(status().isOk());

        mySubscription(owner).andExpect(jsonPath("$.endDate")
                .value(today().plusMonths(1).plusYears(1).toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void switchingFromAPaidPlanQueuesTheNewPlanAfterThePaidPeriod() throws Exception {
        long owner = OWNERS.incrementAndGet();
        long first = checkout(owner, 2, "MONTHLY").get("orderCode").asLong();
        webhook(WebhookFixtures.paid(CHECKSUM, first, 1_499_000)).andExpect(status().isOk());
        long second = checkout(owner, 3, "MONTHLY").get("orderCode").asLong();

        webhook(WebhookFixtures.paid(CHECKSUM, second, 299_000)).andExpect(status().isOk());

        LocalDate paidUntil = today().plusMonths(1);
        mySubscription(owner)
                .andExpect(jsonPath("$.plan.id").value(2))
                .andExpect(jsonPath("$.endDate").value(paidUntil.toString()))
                .andExpect(jsonPath("$.starter").value(false))
                .andExpect(jsonPath("$.upcoming[0].plan.id").value(3))
                .andExpect(jsonPath("$.upcoming[0].startDate").value(paidUntil.plusDays(1).toString()))
                .andExpect(jsonPath("$.upcoming[0].endDate").value(paidUntil.plusDays(1).plusMonths(1).toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ? AND end_date < start_date",
                Integer.class, owner)).isZero();
    }

    @Test
    void theDatabaseRefusesASubscriptionThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO subscriptions (admin_id, plan_id, max_courts, max_branches, "
                + "start_date, end_date) VALUES (?, 3, 5, 1, ?, ?)", OWNERS.incrementAndGet(), today(),
                today().minusDays(1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void webhooksThatCannotBeTrustedAreAcknowledgedButChangeNothing() throws Exception {
        long owner = OWNERS.incrementAndGet();
        long orderCode = checkout(owner, 3, "MONTHLY").get("orderCode").asLong();

        webhook(WebhookFixtures.paid("some-other-key", orderCode, 299_000)).andExpect(status().isOk());
        webhook(WebhookFixtures.paid(CHECKSUM, orderCode, 1_000)).andExpect(status().isOk());
        webhook(WebhookFixtures.paid(CHECKSUM, orderCode + 7_777, 299_000)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM subscription_payments WHERE order_code = ?", String.class,
                orderCode)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE admin_id = ?", Integer.class, owner))
                .isZero();
    }

    @Test
    void aCheckoutPastItsDeadlineReadsAsExpiredButAVerifiedPaymentIsStillHonoured() throws Exception {
        long owner = OWNERS.incrementAndGet();
        long orderCode = checkout(owner, 3, "MONTHLY").get("orderCode").asLong();
        jdbc.update("UPDATE subscription_payments SET expired_at = now() - interval '1 minute' WHERE order_code = ?",
                orderCode);

        mvc.perform(get("/api/subscriptions/payments/" + orderCode).header("Authorization", bearer(owner, "ADMIN")))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
        webhook(WebhookFixtures.paid(CHECKSUM, orderCode, 299_000)).andExpect(status().isOk());

        mvc.perform(get("/api/subscriptions/payments/" + orderCode).header("Authorization", bearer(owner, "ADMIN")))
                .andExpect(jsonPath("$.status").value("PAID"));
        mySubscription(owner).andExpect(jsonPath("$.plan.id").value(3));
    }

    private JsonNode checkout(long owner, long planId, String cycle) throws Exception {
        return body(mvc.perform(post("/api/subscriptions/checkout").header("Authorization", bearer(owner, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutJson(planId, cycle)))
                .andExpect(status().isCreated()));
    }

    private ResultActions mySubscription(long owner) throws Exception {
        return mvc.perform(get("/api/subscriptions/my-subscription").header("Authorization", bearer(owner, "ADMIN")));
    }

    private ResultActions webhook(JsonNode payload) throws Exception {
        return mvc.perform(post("/api/subscriptions/payment-webhook").contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static String checkoutJson(long planId, String cycle) {
        return "{\"planId\":%d,\"billingCycle\":\"%s\"}".formatted(planId, cycle);
    }

    static LocalDate today() {
        return LocalDate.now(VIETNAM);
    }
}
