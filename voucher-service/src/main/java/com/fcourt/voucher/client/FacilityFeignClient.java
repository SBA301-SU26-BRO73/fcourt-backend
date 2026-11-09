package com.fcourt.voucher.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign contract of facility-service (synchronous call 2 of the MSS301 requirement): which branches does the
 * caller own. {@code name} is the Eureka service id; {@code voucher.facility-url} (env {@code FACILITY_URL}) is a
 * fixed URL used when Eureka is disabled (tests, local). Set it to an empty value to resolve
 * {@code facility-service} through Eureka and Spring Cloud LoadBalancer. The caller's Bearer token is forwarded
 * unchanged. Retry, circuit breaker and error mapping are applied by {@code VoucherAuthority}.
 */
@FeignClient(name = "facility-service", url = "${voucher.facility-url:}")
public interface FacilityFeignClient {

    @GetMapping("/api/admin/branches")
    List<FacilityBranch> branches(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
