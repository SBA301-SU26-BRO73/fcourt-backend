package com.fcourt.reporting.integration;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ownership boundary: facility-service decides which branches belong to the caller.
 * The caller's Bearer token is forwarded, so facility applies its own owner scoping.
 */
@Component
public class FacilityClient {
    public record OwnedBranch(Long id, Long adminId, String status, LocalTime openTime, LocalTime closeTime,
                              Integer courtCount) { }

    private final RestClient facility;

    public FacilityClient(@Value("${reporting.facility-url}") String facilityUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.facility = RestClient.builder().baseUrl(facilityUrl).requestFactory(factory).build();
    }

    public OwnedBranch ownedBranch(long branchId, long adminId, String bearer) {
        try {
            OwnedBranch branch = facility.get().uri("/api/admin/branches/{id}", branchId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve().body(OwnedBranch.class);
            validate(branch);
            if (branch.id() != branchId || branch.adminId() != adminId) throw denied();
            return branch;
        } catch (HttpClientErrorException error) {
            int code = error.getStatusCode().value();
            if (code == 401 || code == 403 || code == 404) throw denied();
            throw unavailable();
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    public List<OwnedBranch> ownedBranches(long adminId, String bearer) {
        try {
            List<OwnedBranch> branches = facility.get().uri("/api/admin/branches")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).retrieve()
                    .body(new ParameterizedTypeReference<List<OwnedBranch>>() { });
            if (branches == null) throw unavailable();
            branches.forEach(FacilityClient::validate);
            // Defence in depth against mixing owners: keep only rows that belong to the caller.
            return branches.stream().filter(branch -> branch.adminId() == adminId).toList();
        } catch (HttpClientErrorException error) {
            int code = error.getStatusCode().value();
            if (code == 401 || code == 403) throw denied();
            throw unavailable();
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static void validate(OwnedBranch branch) {
        if (branch == null || branch.id() == null || branch.adminId() == null || branch.openTime() == null
                || branch.closeTime() == null || branch.courtCount() == null) {
            throw unavailable();
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch access denied");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Facility service unavailable");
    }
}
