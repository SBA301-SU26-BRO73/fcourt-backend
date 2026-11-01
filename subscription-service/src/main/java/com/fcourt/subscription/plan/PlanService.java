package com.fcourt.subscription.plan;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.PageResponse;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanRequest;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanRepository.PlanRecord;
import com.fcourt.subscription.plan.PlanRepository.PlanValues;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SaaS plan catalogue. Names are unique among plans that are not deleted; deleting is a soft delete so
 * existing subscriptions keep pointing at the plan they bought.
 */
@Service
public class PlanService {
    private static final String NAME_TAKEN = "Subscription plan name already exists";

    private final PlanRepository plans;
    private final Clock clock;

    PlanService(PlanRepository plans, Clock clock) {
        this.plans = plans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionPlanResponse> list(Pagination pagination) {
        var page = plans.findPage(pagination.size(), pagination.offset());
        return PageResponse.of(withFeatures(page), pagination, plans.count());
    }

    /** Plans on sale for the pricing page, cheapest first. */
    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> listActive() {
        return withFeatures(plans.findActive());
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanResponse get(long id) {
        return withFeatures(List.of(plans.find(id).orElseThrow(PlanService::notFound))).getFirst();
    }

    /** A plan a customer may buy: not deleted and active. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionPlanResponse> findOnSale(long id) {
        return plans.find(id).filter(PlanRecord::active).map(plan -> withFeatures(List.of(plan)).getFirst());
    }

    /** The cheapest plan on sale, used as the starter plan of a court owner without a subscription. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionPlanResponse> cheapestOnSale() {
        return withFeatures(plans.findActive()).stream().findFirst();
    }

    /** Describes a plan even if it was deleted since it was bought. */
    @Transactional(readOnly = true)
    public SubscriptionPlanResponse describe(long id) {
        return withFeatures(List.of(plans.findIncludingDeleted(id).orElseThrow(PlanService::notFound))).getFirst();
    }

    @Transactional
    public SubscriptionPlanResponse create(SubscriptionPlanRequest request) {
        String name = request.name().strip();
        if (plans.nameInUse(name, 0)) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        long id;
        try {
            id = plans.insert(values(request, name, true), clock.instant());
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        plans.replaceFeatures(id, features(request));
        return get(id);
    }

    @Transactional
    public SubscriptionPlanResponse update(long id, SubscriptionPlanRequest request) {
        var current = plans.find(id).orElseThrow(PlanService::notFound);
        String name = request.name().strip();
        if (plans.nameInUse(name, id)) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        try {
            plans.update(id, values(request, name, current.active()), clock.instant());
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict(NAME_TAKEN);
        }
        plans.replaceFeatures(id, features(request));
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        plans.find(id).orElseThrow(PlanService::notFound);
        plans.softDelete(id, clock.instant());
    }

    private List<SubscriptionPlanResponse> withFeatures(List<PlanRecord> records) {
        Map<Long, List<String>> features = plans.featuresOf(records.stream().map(PlanRecord::id).toList());
        return records.stream().map(plan -> new SubscriptionPlanResponse(plan.id(), plan.name(), plan.tagline(),
                plan.monthlyPrice(), plan.yearlyPrice(), plan.maxCourts(), plan.maxBranches(), plan.active(),
                plan.color(), plan.popular(), features.getOrDefault(plan.id(), List.of()), plan.createdAt(),
                plan.updatedAt())).toList();
    }

    private static PlanValues values(SubscriptionPlanRequest request, String name, boolean defaultActive) {
        return new PlanValues(name, blankToNull(request.tagline()), request.monthlyPrice(), request.yearlyPrice(),
                request.maxCourts(), request.maxBranches(),
                request.active() == null ? defaultActive : request.active(), blankToNull(request.color()),
                Boolean.TRUE.equals(request.popular()));
    }

    /** Blank bullets are dropped, the rest trimmed and de-duplicated in the order given. */
    private static List<String> features(SubscriptionPlanRequest request) {
        if (request.features() == null) {
            return List.of();
        }
        return request.features().stream().filter(feature -> feature != null && !feature.isBlank())
                .map(String::strip).distinct().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException notFound() {
        return ApiException.notFound("Subscription plan not found");
    }
}
