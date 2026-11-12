package com.fcourt.subscription.billing;

import com.fcourt.subscription.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service: facility-service asks for an owner's plan limits before creating a branch or court,
 * forwarding the owner's own Bearer token. The gateway blocks /api/internal/**, so it is only reachable
 * inside the cluster. An owner may only read their own limits (SUPER_ADMIN may read any).
 */
@RestController
@RequestMapping("/api/internal/subscriptions")
class InternalLimitsController {
    /** Same field names facility-service's SubscriptionLimitClient.Limits expects. */
    record LimitsResponse(int maxBranches, int maxCourts) {
    }

    private final SubscriptionService subscriptions;
    private final LimitsResponse freeDefaults;

    InternalLimitsController(SubscriptionService subscriptions,
                             @Value("${subscription.free-limits.max-branches:3}") int maxBranches,
                             @Value("${subscription.free-limits.max-courts:15}") int maxCourts) {
        this.subscriptions = subscriptions;
        this.freeDefaults = new LimitsResponse(maxBranches, maxCourts);
    }

    /** The live subscription's limits; owners without one get the free defaults (never 404, never creates rows). */
    @GetMapping("/admins/{adminId}/limits")
    LimitsResponse limits(Authentication authentication, @PathVariable long adminId) {
        boolean superAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equals(authority.getAuthority()));
        long caller = Long.parseLong(((JwtAuthenticationToken) authentication).getToken().getSubject());
        if (!superAdmin && caller != adminId) {
            throw ApiException.forbidden("You are not allowed to perform this action");
        }
        return subscriptions.liveLimits(adminId)
                .map(live -> new LimitsResponse(live.maxBranches(), live.maxCourts()))
                .orElse(freeDefaults);
    }
}
