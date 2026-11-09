package com.fcourt.subscription.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fcourt.subscription.billing.BillingDtos.Usage;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Counts what an owner has already created, using facility-service's {@code GET /api/admin/branches} with the
 * owner's own token (each branch carries {@code courtCount}, deleted rows excluded). Any failure yields null so
 * my-subscription never fails because facility-service is down.
 */
@Component
class FacilityUsageClient {
    private static final Logger log = LoggerFactory.getLogger(FacilityUsageClient.class);

    private final RestClient facility;

    FacilityUsageClient(RestClient.Builder builder,
            @Value("${subscription.facility-url:${FACILITY_URL:http://localhost:8083}}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        facility = builder.clone().requestFactory(factory).baseUrl(url).build();
    }

    /** @return the owner's usage, or null when it cannot be determined. */
    Usage usageOf(long adminId, String bearerToken) {
        try {
            List<Branch> branches = facility.get().uri("/api/admin/branches")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve().body(new ParameterizedTypeReference<List<Branch>>() {});
            if (branches == null) {
                return null;
            }
            int count = 0;
            int courts = 0;
            for (Branch branch : branches) {
                if (branch == null || branch.adminId() == null || branch.adminId() != adminId) {
                    continue;
                }
                count++;
                courts += Math.max(0, branch.courtCount() == null ? 0 : branch.courtCount());
            }
            return new Usage(count, courts);
        } catch (RestClientException | IllegalArgumentException unavailable) {
            log.warn("Facility usage unavailable for owner {}: {}", adminId, unavailable.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(Long adminId, Integer courtCount) {
    }
}
