package com.fcourt.subscription.billing;

import com.fcourt.subscription.billing.BillingDtos.SubscriptionResponse;
import com.fcourt.subscription.billing.BillingDtos.UpcomingSubscription;
import com.fcourt.subscription.billing.SubscriptionRepository.SubscriptionRecord;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A court owner's subscription. Dates are calendar dates in the business time zone (Vietnam), both ends
 * inclusive. An owner without any subscription gets a free one year starter on the cheapest plan the first
 * time they ask for it. Paid periods never overlap and are never cut short: a new plan is queued after the
 * last paid period, and paying again for that same plan extends it. Only the free starter is replaced.
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
        var record = subscriptions.findLive(adminId, today).or(() -> subscriptions.findLatest(adminId, today))
                .orElseGet(() -> createStarter(adminId, today));
        return toResponse(record, today);
    }

    /**
     * Applies a paid plan. The free starter is withdrawn first (ended yesterday, or removed if it only started
     * today). Then, if paid time is still running or queued, the new period starts the day after the last one
     * ends, or extends the last one when it is the same plan, so no paid day is lost and nothing overlaps.
     * Without paid time the new period starts today.
     */
    @Transactional
    public void activate(long adminId, SubscriptionPlanResponse plan, BillingCycle cycle) {
        subscriptions.lockOwner(adminId);
        LocalDate today = today();
        Instant now = clock.instant();
        SubscriptionRecord lastPaid = null;
        for (var subscription : subscriptions.findNotEnded(adminId, today)) {
            if (subscription.starter()) {
                withdraw(subscription, today, now);
            } else if (lastPaid == null || subscription.endDate().isAfter(lastPaid.endDate())) {
                lastPaid = subscription;
            }
        }
        if (lastPaid != null && lastPaid.planId() == plan.id()) {
            subscriptions.renew(lastPaid.id(), cycle.addTo(lastPaid.endDate()), plan.maxCourts(),
                    plan.maxBranches(), now);
            return;
        }
        LocalDate start = lastPaid == null ? today : lastPaid.endDate().plusDays(1);
        subscriptions.insert(adminId, plan.id(), plan.maxCourts(), plan.maxBranches(), start, cycle.addTo(start),
                false, now);
    }

    /** Ends a starter yesterday; one that started today (or later) never ran, so it is removed instead. */
    private void withdraw(SubscriptionRecord starter, LocalDate today, Instant now) {
        if (starter.startDate().isBefore(today)) {
            subscriptions.end(starter.id(), today.minusDays(1), now);
        } else {
            subscriptions.softDelete(starter.id(), now);
        }
    }

    private SubscriptionRecord createStarter(long adminId, LocalDate today) {
        subscriptions.lockOwner(adminId);
        // Another request may have created it while this one waited for the lock.
        return subscriptions.findLive(adminId, today).or(() -> subscriptions.findLatest(adminId, today))
                .orElseGet(() -> {
                    var plan = plans.cheapestOnSale()
                            .orElseThrow(() -> ApiException.conflict("No subscription plan is available yet"));
                    LocalDate end = today.plusYears(1);
                    long id = subscriptions.insert(adminId, plan.id(), plan.maxCourts(), plan.maxBranches(), today,
                            end, true, clock.instant());
                    return new SubscriptionRecord(id, adminId, plan.id(), plan.maxCourts(), plan.maxBranches(),
                            today, end, true);
                });
    }

    private SubscriptionResponse toResponse(SubscriptionRecord record, LocalDate today) {
        var upcoming = subscriptions.findNotEnded(record.adminId(), today).stream()
                .filter(next -> next.startDate().isAfter(today))
                .map(next -> new UpcomingSubscription(next.id(), plans.describe(next.planId()), next.maxCourts(),
                        next.maxBranches(), next.startDate(), next.endDate()))
                .toList();
        boolean active = !record.startDate().isAfter(today) && !record.endDate().isBefore(today);
        return new SubscriptionResponse(record.id(), record.adminId(), plans.describe(record.planId()),
                record.maxCourts(), record.maxBranches(), record.startDate(), record.endDate(), active,
                record.starter(), upcoming);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
