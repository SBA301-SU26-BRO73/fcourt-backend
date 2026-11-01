package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.subscription.billing.SubscriptionRepository.SubscriptionRecord;
import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SubscriptionServiceTest {
    // 02:00 on 2 November in Vietnam, still 1 November in UTC: the business date must follow the zone.
    static final Instant NOW = Instant.parse("2026-11-01T19:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    static final long ADMIN = 7;

    SubscriptionRepository subscriptions;
    PlanService plans;
    SubscriptionService service;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionRepository.class);
        plans = mock(PlanService.class);
        service = new SubscriptionService(subscriptions, plans, Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Ho_Chi_Minh");
        when(plans.describe(anyLong())).thenAnswer(call -> plan(call.getArgument(0), "Plan " + call.getArgument(0)));
    }

    @Test
    void currentReturnsTheLiveSubscriptionAsActiveWithItsPlan() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 3, TODAY.minusDays(5), TODAY.plusDays(25))));

        var response = service.current(ADMIN);

        assertThat(response.id()).isEqualTo(1);
        assertThat(response.adminId()).isEqualTo(ADMIN);
        assertThat(response.plan().id()).isEqualTo(3);
        assertThat(response.active()).isTrue();
        assertThat(response.endDate()).isEqualTo(TODAY.plusDays(25));
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void aSubscriptionThatEndsTodayIsStillActive() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 3, TODAY.minusDays(30), TODAY)));

        assertThat(service.current(ADMIN).active()).isTrue();
    }

    @Test
    void whenNothingIsLiveTheLatestLapsedOneIsReturnedAsInactive() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN)).thenReturn(Optional.of(record(2, 3, TODAY.minusDays(60), TODAY.minusDays(1))));

        var response = service.current(ADMIN);

        assertThat(response.active()).isFalse();
        assertThat(response.id()).isEqualTo(2);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void anOwnerWithoutASubscriptionGetsAOneYearStarterOnTheCheapestPlan() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN)).thenReturn(Optional.empty());
        when(plans.cheapestOnSale()).thenReturn(Optional.of(plan(3, "Basic")));
        when(subscriptions.insert(ADMIN, 3, 5, 1, TODAY, TODAY.plusYears(1), NOW)).thenReturn(11L);

        var response = service.current(ADMIN);

        var order = inOrder(subscriptions);
        order.verify(subscriptions).lockOwner(ADMIN);
        order.verify(subscriptions).insert(ADMIN, 3, 5, 1, TODAY, TODAY.plusYears(1), NOW);
        assertThat(response.id()).isEqualTo(11);
        assertThat(response.active()).isTrue();
        assertThat(response.maxCourts()).isEqualTo(5);
    }

    @Test
    void theStarterIsNotCreatedTwiceWhenAConcurrentRequestWonTheLock() {
        var created = record(12, 3, TODAY, TODAY.plusYears(1));
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty(), Optional.of(created));
        when(subscriptions.findLatest(ADMIN)).thenReturn(Optional.empty());

        var response = service.current(ADMIN);

        assertThat(response.id()).isEqualTo(12);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void withoutAnyPlanOnSaleThereIsNothingToStartWith() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN)).thenReturn(Optional.empty());
        when(plans.cheapestOnSale()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(ADMIN)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getMessage()).isEqualTo("No subscription plan is available yet");
        });
    }

    @Test
    void renewingThePlanYouHaveExtendsFromItsEndDateSoNoPaidDaysAreLost() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 2, TODAY.minusDays(20), TODAY.plusDays(10))));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);

        var order = inOrder(subscriptions);
        order.verify(subscriptions).lockOwner(ADMIN);
        order.verify(subscriptions).renew(1, TODAY.plusDays(10).plusMonths(1), 5, 1, NOW);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void renewingOnTheLastDayCountsFromTodayAndAYearlyCycleAddsAYear() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 2, TODAY.minusDays(30), TODAY)));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.YEARLY);

        verify(subscriptions).renew(1, TODAY.plusYears(1), 5, 1, NOW);
    }

    @Test
    void renewalPicksUpTheCurrentLimitsOfThePlan() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 2, TODAY.minusDays(20), TODAY.plusDays(10))));

        service.activate(ADMIN, new SubscriptionPlanResponse(2, "Pro", null, 1L, 1L, 40, 8, true, null, false,
                List.of(), null, null), BillingCycle.MONTHLY);

        verify(subscriptions).renew(1, TODAY.plusDays(10).plusMonths(1), 40, 8, NOW);
    }

    @Test
    void switchingPlanEndsTheLiveOnesYesterdayAndStartsTheNewOneToday() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 3, TODAY.minusDays(20), TODAY.plusDays(10))));
        when(subscriptions.findAllLive(ADMIN, TODAY)).thenReturn(List.of(
                record(1, 3, TODAY.minusDays(20), TODAY.plusDays(10)), record(2, 3, TODAY, TODAY.plusDays(40))));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.YEARLY);

        verify(subscriptions).end(1, TODAY.minusDays(1), NOW);
        verify(subscriptions).end(2, TODAY.minusDays(1), NOW);
        verify(subscriptions).insert(ADMIN, 2, 5, 1, TODAY, TODAY.plusYears(1), NOW);
        verify(subscriptions, never()).renew(anyLong(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void anOwnerWhoseSubscriptionLapsedStartsFreshFromToday() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findAllLive(ADMIN, TODAY)).thenReturn(List.of());

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);

        verify(subscriptions).insert(ADMIN, 2, 5, 1, TODAY, TODAY.plusMonths(1), NOW);
        verify(subscriptions, never()).end(anyLong(), any(), any());
    }

    static SubscriptionRecord record(long id, long planId, LocalDate start, LocalDate end) {
        return new SubscriptionRecord(id, ADMIN, planId, 5, 1, start, end);
    }

    static SubscriptionPlanResponse plan(long id, String name) {
        return new SubscriptionPlanResponse(id, name, null, 299_000L, 2_990_000L, 5, 1, true, null, false,
                List.of(), OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
