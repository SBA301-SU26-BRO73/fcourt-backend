package com.fcourt.voucher;

import com.fcourt.voucher.client.FacilityBranch;
import com.fcourt.voucher.client.FacilityFeignClient;
import com.fcourt.voucher.config.FacilityResilienceConfig;
import feign.FeignException;
import io.github.resilience4j.retry.Retry;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which branches may the caller manage. facility-service decides (the caller's token is forwarded); the call is the
 * OpenFeign interface {@link FacilityFeignClient} wrapped in Resilience4J retry and circuit breaker. On failure the
 * behaviour is the one from before OpenFeign: 401/403 from facility become 403, anything else (including an open
 * circuit) becomes 503 "Branch verification unavailable".
 */
@Component
class VoucherAuthority {
    private static final Logger log = LoggerFactory.getLogger(VoucherAuthority.class);

    private final FacilityFeignClient facility;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    VoucherAuthority(FacilityFeignClient facility, CircuitBreakerFactory<?, ?> circuitBreakers, Retry facilityRetry) {
        this.facility = facility;
        this.circuitBreaker = circuitBreakers.create(FacilityResilienceConfig.NAME);
        this.retry = facilityRetry;
    }

    List<Long> branches(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long actor;
        try {
            actor = Long.parseLong(jwt.getSubject());
            if (actor <= 0) throw new NumberFormatException();
        } catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        Object claim = jwt.getClaims().get("roles");
        Collection<?> roles = claim instanceof Collection<?> values ? values : List.of(
                Objects.toString(jwt.getClaims().get("role"), ""));
        boolean superAdmin = roles.contains("SUPER_ADMIN");
        if (!superAdmin && !roles.contains("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<FacilityBranch> rows = call(() -> facility.branches("Bearer " + jwt.getTokenValue()));
        if (rows == null) throw unavailable();
        var ids = new TreeSet<Long>();
        for (FacilityBranch row : rows) {
            if (row == null || row.id() == null || row.id() <= 0 || row.adminId() == null || row.adminId() <= 0
                    || (!superAdmin && row.adminId() != actor) || !ids.add(row.id())) throw unavailable();
        }
        return List.copyOf(ids);
    }

    /** One logical call = retries inside one circuit breaker sample; the fallback maps every failure. */
    private <T> T call(Supplier<T> request) {
        return circuitBreaker.run(Retry.decorateSupplier(retry, request), thrown -> {
            // The breaker may wrap the failure (ExecutionException / CompletionException).
            Throwable error = thrown;
            while (error.getCause() != null && !(error instanceof FeignException)) error = error.getCause();
            if (error instanceof FeignException feign && (feign.status() == 401 || feign.status() == 403))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            log.warn("facility-service call failed: {}", error.toString());
            throw unavailable();
        });
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Branch verification unavailable");
    }
}
