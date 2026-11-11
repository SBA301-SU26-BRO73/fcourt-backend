package com.fcourt.voucher.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * OpenFeign contract of facility-service (synchronous call 2 of the MSS301 requirement): which branches does the
 * caller own. {@code voucher.facility-url} (env {@code FACILITY_URL}) is the fixed URL of facility-service (there is no service registry). The caller's Bearer token is forwarded
 * unchanged. Retry, circuit breaker and error mapping are applied by {@code VoucherAuthority}.
 */
@FeignClient(name = "facility-service", url = "${voucher.facility-url:}")
public interface FacilityFeignClient {

    @GetMapping("/api/admin/branches")
    List<FacilityBranch> branches(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
