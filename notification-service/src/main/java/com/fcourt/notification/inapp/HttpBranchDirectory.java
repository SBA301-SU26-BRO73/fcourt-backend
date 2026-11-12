package com.fcourt.notification.inapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Internal, in-cluster calls (neither path is routed by the gateway):
 * <ul>
 *   <li>{@code GET facility-service /api/internal/branches/{id}/owner}, open like the other internal GET routes of
 *       facility-service.</li>
 *   <li>{@code GET staff-operations-service /api/internal/branches/{id}/staff-user-ids} with the shared
 *       {@code X-Internal-Service-Token} ({@code STAFF_DIRECTORY_TOKEN}).</li>
 * </ul>
 */
@Component
class HttpBranchDirectory implements BranchDirectory {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final Logger log = LoggerFactory.getLogger(HttpBranchDirectory.class);

    private final RestClient facility;
    private final RestClient staff;
    private final String staffToken;

    HttpBranchDirectory(@Value("${notification.directory.facility-url}") String facilityUrl,
                        @Value("${notification.directory.staff-url}") String staffUrl,
                        @Value("${notification.directory.staff-token:}") String staffToken) {
        this.facility = client(facilityUrl);
        this.staff = client(staffUrl);
        this.staffToken = staffToken == null ? "" : staffToken;
    }

    private static RestClient client(String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public OptionalLong ownerOf(long branchId) {
        try {
            var owner = facility.get().uri("/api/internal/branches/{id}/owner", branchId)
                    .retrieve().body(Owner.class);
            return owner == null || owner.ownerId() == null || owner.ownerId() <= 0
                    ? OptionalLong.empty() : OptionalLong.of(owner.ownerId());
        } catch (HttpClientErrorException.NotFound missing) {
            return OptionalLong.empty();
        }
        // Any other failure propagates: the Kafka error handler retries the event.
    }

    @Override
    public List<Long> staffOf(long branchId) {
        if (staffToken.isBlank()) {
            log.warn("The staff directory token is not set; branch staff get no in-app notification");
            return List.of();
        }
        try {
            var staffIds = staff.get().uri("/api/internal/branches/{id}/staff-user-ids", branchId)
                    .header(TOKEN_HEADER, staffToken).retrieve().body(Staff.class);
            return staffIds == null || staffIds.userIds() == null ? List.of() : staffIds.userIds();
        } catch (HttpServerErrorException | HttpClientErrorException error) {
            log.warn("Staff directory answered {} for branch {}; staff skipped", error.getStatusCode().value(), branchId);
            return List.of();
        } catch (RestClientException error) {
            log.warn("Staff directory unreachable for branch {}; staff skipped", branchId);
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Owner(Long ownerId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Staff(List<Long> userIds) {
    }
}
