package com.fcourt.facility.integration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * SaaS plan limits of a court owner.
 *
 * <p>subscription-service (SCRUM-10) is not implemented yet. When {@code SUBSCRIPTION_BASE_URL}
 * is set, the proposed contract {@code GET /api/internal/subscriptions/admins/{adminId}/limits}
 * (forwarding the caller's Bearer) must return {@code {"maxBranches":3,"maxCourts":15}}; 404 means
 * the owner has no active subscription. Otherwise the configured default limits apply. To be wired
 * to the real endpoint when SCRUM-10 lands.
 */
@Component
public class SubscriptionLimitClient {
    public record Limits(int maxBranches, int maxCourts) { }

    private final RestClient client;
    private final Limits defaults;

    public SubscriptionLimitClient(@Value("${facility.subscription.base-url:}") String baseUrl,
                                   @Value("${facility.subscription.default-max-branches:3}") int maxBranches,
                                   @Value("${facility.subscription.default-max-courts:15}") int maxCourts) {
        this.defaults = new Limits(maxBranches, maxCourts);
        if (baseUrl == null || baseUrl.isBlank()) {
            this.client = null;
        } else {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofSeconds(2));
            this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
    }

    public Limits limits(long adminId, String bearerToken) {
        if (client == null) return defaults;
        try {
            Limits limits = client.get().uri("/api/internal/subscriptions/admins/{id}/limits", adminId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve().body(Limits.class);
            if (limits == null || limits.maxBranches() < 0 || limits.maxCourts() < 0) throw unavailable();
            return limits;
        } catch (HttpClientErrorException.NotFound noSubscription) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No active subscription plan");
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Subscription service unavailable");
    }
}
