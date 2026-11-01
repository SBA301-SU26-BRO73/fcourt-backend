package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fcourt.subscription.billing.BillingDtos.PaymentResponse;
import com.fcourt.subscription.billing.BillingDtos.SubscriptionResponse;
import com.fcourt.subscription.billing.PaymentService.WebhookOutcome;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.ApiExceptionHandler;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Request binding, caller identity, validation and the webhook acknowledgement rules. */
class BillingControllerTest {
    SubscriptionService subscriptions;
    PaymentService payments;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionService.class);
        payments = mock(PaymentService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new BillingController(subscriptions, payments))
                .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
                // Same date format as the running service (Spring Boot writes ISO-8601, not timestamps).
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }

    @Test
    void mySubscriptionIsLookedUpForTheSubjectOfTheToken() throws Exception {
        when(subscriptions.current(7)).thenReturn(new SubscriptionResponse(1, 7, plan(), 15, 3,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), true, false,
                java.util.List.of(new BillingDtos.UpcomingSubscription(2, plan(), 15, 3, LocalDate.of(2026, 12, 2),
                        LocalDate.of(2027, 1, 2)))));

        mvc.perform(get("/api/subscriptions/my-subscription").principal(token(7, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminId").value(7))
                .andExpect(jsonPath("$.plan.name").value("Pro"))
                .andExpect(jsonPath("$.endDate").value("2026-12-01"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.starter").value(false))
                .andExpect(jsonPath("$.upcoming[0].startDate").value("2026-12-02"));
    }

    @Test
    void checkoutAnswers201WithThePaymentAndUsesTheCallerAsOwner() throws Exception {
        when(payments.checkout(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(payment("PENDING"));

        mvc.perform(post("/api/subscriptions/checkout").principal(token(7, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":2,\"billingCycle\":\"MONTHLY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderCode").value(123))
                .andExpect(jsonPath("$.checkoutUrl").value("https://pay.payos.vn/web/link-1"));

        var request = ArgumentCaptor.forClass(BillingDtos.CheckoutRequest.class);
        verify(payments).checkout(org.mockito.ArgumentMatchers.eq(7L), request.capture());
        assertThat(request.getValue().planId()).isEqualTo(2);
        assertThat(request.getValue().billingCycle()).isEqualTo("MONTHLY");
    }

    @Test
    void checkoutRequiresAPlanAndACycle() throws Exception {
        mvc.perform(post("/api/subscriptions/checkout").principal(token(7, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"billingCycle\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        verify(payments, never()).checkout(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void aGatewayProblemComesBackAsAProblemDocument() throws Exception {
        when(payments.checkout(org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(ApiException.unavailable("Payment gateway is not configured"));

        mvc.perform(post("/api/subscriptions/checkout").principal(token(7, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":2,\"billingCycle\":\"MONTHLY\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Payment gateway is not configured"));
    }

    @Test
    void anOwnerReadsAPaymentAsThemselvesAndASuperAdminAsSuperAdmin() throws Exception {
        when(payments.get(123, 7, false)).thenReturn(payment("PAID"));
        when(payments.get(123, 1, true)).thenReturn(payment("PAID"));

        mvc.perform(get("/api/subscriptions/payments/123").principal(token(7, "ROLE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/subscriptions/payments/123").principal(token(1, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingPaymentIsAProblemNotAServerError() throws Exception {
        when(payments.get(5, 7, false)).thenThrow(ApiException.notFound("Payment not found"));

        mvc.perform(get("/api/subscriptions/payments/5").principal(token(7, "ROLE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Payment not found"));
    }

    @Test
    void theWebhookIsAcknowledgedAfterAnAcceptedPayload() throws Exception {
        when(payments.handleWebhook(any())).thenReturn(WebhookOutcome.ACTIVATED);

        mvc.perform(post("/api/subscriptions/payment-webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"orderCode\":123},\"signature\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(payments).handleWebhook(payload.capture());
        assertThat(payload.getValue().get("signature").asText()).isEqualTo("abc");
    }

    @Test
    void anUntrustedWebhookIsStillAcknowledgedSoCallersLearnNothing() throws Exception {
        when(payments.handleWebhook(any())).thenThrow(new WebhookRejectedException("Invalid PayOS webhook signature"));

        mvc.perform(post("/api/subscriptions/payment-webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));
    }

    @Test
    void aServerFaultWhileHandlingTheWebhookIsNotSwallowedSoPayOsRetries() {
        when(payments.handleWebhook(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> mvc.perform(post("/api/subscriptions/payment-webhook")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aGenericIllegalArgumentFromAFrameworkIsAServerFaultNotARejectedPayload() {
        when(payments.handleWebhook(any())).thenThrow(new IllegalArgumentException("bad SQL parameter"));

        assertThatThrownBy(() -> mvc.perform(post("/api/subscriptions/payment-webhook")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWebhookOnlyAcceptsJson() throws Exception {
        mvc.perform(post("/api/subscriptions/payment-webhook").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    static JwtAuthenticationToken token(long userId, String authority) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(Long.toString(userId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)));
    }

    static PaymentResponse payment(String status) {
        boolean pending = "PENDING".equals(status);
        return new PaymentResponse(11, 123, 2, "Pro", "MONTHLY", 299_000, status,
                pending ? "https://pay.payos.vn/web/link-1" : null, null, OffsetDateTime.parse("2026-11-01T10:15:00Z"),
                null, "PAID".equals(status));
    }

    static SubscriptionPlanResponse plan() {
        return new SubscriptionPlanResponse(2, "Pro", null, 299_000L, 2_990_000L, 15, 3, true, null, false,
                List.of(), OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
