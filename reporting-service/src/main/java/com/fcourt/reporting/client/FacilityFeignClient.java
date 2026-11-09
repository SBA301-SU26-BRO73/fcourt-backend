package com.fcourt.reporting.client;

import com.fcourt.reporting.integration.FacilityClient.CourtInfo;
import com.fcourt.reporting.integration.FacilityClient.OwnedBranch;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign contract of facility-service (synchronous call 1 of the MSS301 requirement).
 *
 * <p>{@code name} is the Eureka service id. {@code reporting.facility-url} (env {@code FACILITY_URL}) is a fixed
 * URL used when Eureka is disabled (tests, local); set it to an empty value to resolve {@code facility-service}
 * through Eureka and Spring Cloud LoadBalancer. The caller's Bearer token is forwarded unchanged.
 * Resilience (retry, circuit breaker, error mapping) is applied by {@code FacilityClient}.
 */
@FeignClient(name = "facility-service", url = "${reporting.facility-url:}")
public interface FacilityFeignClient {

    @GetMapping("/api/admin/branches/{id}")
    OwnedBranch ownedBranch(@PathVariable("id") long branchId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    @GetMapping("/api/admin/branches")
    List<OwnedBranch> branches(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    @GetMapping("/api/admin/branches/{id}/courts")
    List<CourtInfo> courts(@PathVariable("id") long branchId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
