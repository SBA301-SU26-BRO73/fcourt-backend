package com.fcourt.subscription.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fcourt.subscription.billing.BillingDtos.CheckoutRequest;
import com.fcourt.subscription.billing.BillingDtos.PaymentResponse;
import com.fcourt.subscription.billing.BillingDtos.SubscriptionResponse;
import com.fcourt.subscription.billing.BillingDtos.WebhookAck;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Court owners (ADMIN) and the super admin buy and view subscriptions; the PayOS webhook is public
 * and authenticated by its signature. Enforced in SecurityConfiguration.
 */
@RestController
@RequestMapping("/api/subscriptions")
class BillingController {
    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final SubscriptionService subscriptions;
    private final PaymentService payments;

    private final FacilityUsageClient usage;

    BillingController(SubscriptionService subscriptions, PaymentService payments, FacilityUsageClient usage) {
        this.usage = usage;
        this.subscriptions = subscriptions;
        this.payments = payments;
    }

    @GetMapping("/my-subscription")
    SubscriptionResponse mySubscription(Authentication authentication) {
        long adminId = userId(authentication);
        var response = subscriptions.current(adminId);
        // Usage is best effort: null when facility-service is unavailable.
        var token = ((JwtAuthenticationToken) authentication).getToken().getTokenValue();
        return response.withUsage(usage.usageOf(adminId, token));
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentResponse checkout(Authentication authentication, @Valid @RequestBody CheckoutRequest request) {
        return payments.checkout(userId(authentication), request);
    }

    @GetMapping("/payments/{orderCode}")
    PaymentResponse payment(Authentication authentication, @PathVariable long orderCode) {
        boolean superAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equals(authority.getAuthority()));
        return payments.get(orderCode, userId(authentication), superAdmin);
    }

    /**
     * PayOS only needs a 2xx. Payloads that cannot be trusted are logged and still acknowledged so the
     * endpoint reveals nothing to a caller guessing at it; a server fault propagates as 5xx so PayOS retries.
     */
    @PostMapping(value = "/payment-webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    WebhookAck paymentWebhook(@RequestBody JsonNode payload) {
        try {
            var outcome = payments.handleWebhook(payload);
            log.info("PayOS webhook handled: {}", outcome);
        } catch (WebhookRejectedException rejected) {
            log.warn("PayOS webhook rejected: {}", rejected.getMessage());
        }
        return new WebhookAck(true);
    }

    private static long userId(Authentication authentication) {
        // The JwtDecoder already guarantees a positive numeric subject.
        return Long.parseLong(((JwtAuthenticationToken) authentication).getToken().getSubject());
    }
}
