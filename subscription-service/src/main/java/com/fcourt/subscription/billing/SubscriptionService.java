package com.fcourt.subscription.billing;

import com.fcourt.subscription.billing.BillingDtos.SubscriptionResponse;
import com.fcourt.subscription.billing.SubscriptionRepository.SubscriptionRecord;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A court owner's subscription. Dates are calendar dates in the business time zone (Vietnam) and a
 * subscription is live until its {@code end_date} has passed. An owner without any subscription gets
 * a one year starter subscription on the cheapest plan the first time they ask for it.
 */
@Service
public class SubscriptionService {
    private final SubscriptionRepository subscriptions;
    private final PlanService plans;
    private final Clock clock;
    private final ZoneId zone;

    SubscriptionService(SubscriptionRepository subscriptions, PlanService plans, Clock clock,
                        @Value("${subscription.time-zone:Asia/Ho_Chi_Minh}") String zone) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /** The live subscription, else the latest one that lapsed, else a freshly created starter one. */
    @Transactional
    public SubscriptionResponse current(long adminId) {
        LocalDate today = today();
        var record = subscriptions.findLive(adminId, today).or(() -> subscriptions.findLatest(adminId))
                .orElseGet(() -> createStarter(adminId, today));
        return toResponse(record, today);
    }

    /**
     * Applies a paid plan. Renewing the plan the owner already has extends it from its current end date, so
     * paying early loses no days. Any other plan replaces the live ones from today.
     */
    @Transactional
    public void activate(long adminId, SubscriptionPlanResponse plan, BillingCycle cycle) {
        subscriptions.lockOwner(adminId);
        LocalDate today = today();
        var now = clock.instant();
        var live = subscriptions.findLive(adminId, today);
        if (live.isPresent() && live.get().planId() == plan.id()) {
            LocalDate base = live.get().endDate().isAfter(today) ? live.get().endDate() : today;
            subscriptions.renew(live.get().id(), cycle.addTo(base), plan.maxCourts(), plan.maxBranches(), now);
            return;
        }
        subscriptions.findAllLive(adminId, today)
                .forEach(old -> subscriptions.end(old.id(), today.minusDays(1), now));
        subscriptions.insert(adminId, plan.id(), plan.maxCourts(), plan.maxBranches(), today,
                cycle.addTo(today), now);
    }

    private SubscriptionRecord createStarter(long adminId, LocalDate today) {
        subscriptions.lockOwner(adminId);
        // Another request may have created it while this one waited for the lock.
        return subscriptions.findLive(adminId, today).or(() -> subscriptions.findLatest(adminId)).orElseGet(() -> {
            var plan = plans.cheapestOnSale()
                    .orElseThrow(() -> ApiException.conflict("No subscription plan is available yet"));
            LocalDate end = today.plusYears(1);
            long id = subscriptions.insert(adminId, plan.id(), plan.maxCourts(), plan.maxBranches(), today, end,
                    clock.instant());
            return new SubscriptionRecord(id, adminId, plan.id(), plan.maxCourts(), plan.maxBranches(), today, end);
        });
    }

    private SubscriptionResponse toResponse(SubscriptionRecord record, LocalDate today) {
        return new SubscriptionResponse(record.id(), record.adminId(), plans.describe(record.planId()),
                record.maxCourts(), record.maxBranches(), record.startDate(), record.endDate(),
                !record.endDate().isBefore(today));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
