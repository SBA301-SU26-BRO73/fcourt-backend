package com.fcourt.reporting.integration;

import com.fcourt.reporting.client.FacilityFeignClient;
import com.fcourt.reporting.config.FacilityResilienceConfig;
import feign.FeignException;
import io.github.resilience4j.retry.Retry;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ownership boundary: facility-service decides which branches belong to the caller.
 * The caller's Bearer token is forwarded, so facility applies its own owner scoping.
 *
 * <p>The HTTP call itself is the OpenFeign interface {@link FacilityFeignClient}; this class adds Resilience4J
 * (retry + circuit breaker) and maps failures to the same {@link ResponseStatusException}s as before.
 */
@Component
public class FacilityClient {
    public record OwnedBranch(Long id, Long adminId, String status, LocalTime openTime, LocalTime closeTime,
                              Integer courtCount) { }

    /** Court of an owned branch; {@code name} is unique inside its branch (case-insensitive). */
    public record CourtInfo(Long id, Long branchId, String name, String status) { }

    private static final Logger log = LoggerFactory.getLogger(FacilityClient.class);

    private final FacilityFeignClient facility;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public FacilityClient(FacilityFeignClient facility, CircuitBreakerFactory<?, ?> circuitBreakers,
                          Retry facilityRetry) {
        this.facility = facility;
        this.circuitBreaker = circuitBreakers.create(FacilityResilienceConfig.NAME);
        this.retry = facilityRetry;
    }

    public OwnedBranch ownedBranch(long branchId, long adminId, String bearer) {
        OwnedBranch branch = call(() -> facility.ownedBranch(branchId, "Bearer " + bearer), 401, 403, 404);
        validate(branch);
        if (branch.id() != branchId || branch.adminId() != adminId) throw denied();
        return branch;
    }

    public List<OwnedBranch> ownedBranches(long adminId, String bearer) {
        List<OwnedBranch> branches = call(() -> facility.branches("Bearer " + bearer), 401, 403);
        if (branches == null) throw unavailable();
        branches.forEach(FacilityClient::validate);
        // Defence in depth against mixing owners: keep only rows that belong to the caller.
        return branches.stream().filter(branch -> branch.adminId() == adminId).toList();
    }

    /** Every branch of the platform; facility-service returns them all only to a SUPER_ADMIN token. */
    public List<OwnedBranch> allBranches(String bearer) {
        List<OwnedBranch> branches = call(() -> facility.branches("Bearer " + bearer), 401, 403);
        if (branches == null) throw unavailable();
        branches.forEach(FacilityClient::validate);
        return branches;
    }

    /** Courts of one branch the caller owns (facility enforces ownership with the forwarded token). */
    public List<CourtInfo> courts(long branchId, String bearer) {
        List<CourtInfo> courts = call(() -> facility.courts(branchId, "Bearer " + bearer), 401, 403, 404);
        if (courts == null || courts.stream().anyMatch(court -> court == null || court.id() == null
                || court.name() == null || court.status() == null)) throw unavailable();
        return courts;
    }

    /**
     * Runs one Feign call under the circuit breaker, with retries inside it (one logical call is one breaker
     * sample). The fallback keeps the behaviour from before OpenFeign: the listed 4xx codes become 403
     * "Branch access denied"; every other failure, including an open circuit, becomes 503 "Facility service
     * unavailable".
     */
    private <T> T call(Supplier<T> request, int... deniedStatuses) {
        return circuitBreaker.run(Retry.decorateSupplier(retry, request), error -> {
            throw translate(error, deniedStatuses);
        });
    }

    private static ResponseStatusException translate(Throwable thrown, int[] deniedStatuses) {
        // The breaker may wrap the failure (ExecutionException / CompletionException).
        Throwable error = thrown;
        while (error.getCause() != null && !(error instanceof FeignException)
                && !(error instanceof ResponseStatusException)) error = error.getCause();
        if (error instanceof ResponseStatusException status) return status;
        if (error instanceof FeignException feign) {
            for (int denied : deniedStatuses) {
                if (feign.status() == denied) return denied();
            }
        }
        log.warn("facility-service call failed: {}", error.toString());
        return unavailable();
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
