package com.fcourt.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aSubscriptionThatEndsTodayIsStillActive() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(record(1, 3, TODAY.minusDays(30), TODAY)));

        assertThat(service.current(ADMIN).active()).isTrue();
    }

    @Test
    void whenNothingIsLiveTheLatestLapsedOneIsReturnedAsInactive() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN, TODAY)).thenReturn(Optional.of(record(2, 3, TODAY.minusDays(60), TODAY.minusDays(1))));

        var response = service.current(ADMIN);

        assertThat(response.active()).isFalse();
        assertThat(response.id()).isEqualTo(2);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), anyBoolean(), any());
    }

    @Test
    void anOwnerWithoutASubscriptionGetsAOneYearStarterOnTheCheapestPlan() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(plans.cheapestOnSale()).thenReturn(Optional.of(plan(3, "Basic")));
        when(subscriptions.insert(ADMIN, 3, 5, 1, TODAY, TODAY.plusYears(1), true, NOW)).thenReturn(11L);

        var response = service.current(ADMIN);

        var order = inOrder(subscriptions);
        order.verify(subscriptions).lockOwner(ADMIN);
        order.verify(subscriptions).insert(ADMIN, 3, 5, 1, TODAY, TODAY.plusYears(1), true, NOW);
        assertThat(response.id()).isEqualTo(11);
        assertThat(response.active()).isTrue();
        assertThat(response.maxCourts()).isEqualTo(5);
        assertThat(response.starter()).isTrue();
        assertThat(response.upcoming()).isEmpty();
    }

    @Test
    void theStarterIsNotCreatedTwiceWhenAConcurrentRequestWonTheLock() {
        var created = record(12, 3, TODAY, TODAY.plusYears(1));
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty(), Optional.of(created));
        when(subscriptions.findLatest(ADMIN, TODAY)).thenReturn(Optional.empty());

        var response = service.current(ADMIN);

        assertThat(response.id()).isEqualTo(12);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), anyBoolean(), any());
    }

    @Test
    void withoutAnyPlanOnSaleThereIsNothingToStartWith() {
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(subscriptions.findLatest(ADMIN, TODAY)).thenReturn(Optional.empty());
        when(plans.cheapestOnSale()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(ADMIN)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getMessage()).isEqualTo("No subscription plan is available yet");
        });
    }

    @Test
    void currentListsThePaidPeriodsQueuedBehindTheLiveOne() {
        var live = record(1, 2, TODAY.minusDays(20), TODAY.plusDays(10));
        when(subscriptions.findLive(ADMIN, TODAY)).thenReturn(Optional.of(live));
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(live,
                record(2, 3, TODAY.plusDays(11), TODAY.plusDays(11).plusMonths(1))));

        var response = service.current(ADMIN);

        assertThat(response.starter()).isFalse();
        assertThat(response.upcoming()).singleElement().satisfies(next -> {
            assertThat(next.id()).isEqualTo(2);
            assertThat(next.plan().id()).isEqualTo(3);
            assertThat(next.startDate()).isEqualTo(TODAY.plusDays(11));
        });
    }

    @Test
    void renewingThePlanYouHaveExtendsFromItsEndDateSoNoPaidDaysAreLost() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(record(1, 2, TODAY.minusDays(20), TODAY.plusDays(10))));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);

        var order = inOrder(subscriptions);
        order.verify(subscriptions).lockOwner(ADMIN);
        order.verify(subscriptions).renew(1, TODAY.plusDays(10).plusMonths(1), 5, 1, NOW);
        verify(subscriptions, never()).insert(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), anyBoolean(), any());
    }

    @Test
    void renewingOnTheLastDayCountsFromThatDayAndAYearlyCycleAddsAYear() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(record(1, 2, TODAY.minusDays(30), TODAY)));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.YEARLY);

        verify(subscriptions).renew(1, TODAY.plusYears(1), 5, 1, NOW);
    }

    @Test
    void renewalPicksUpTheCurrentLimitsOfThePlan() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(record(1, 2, TODAY.minusDays(20), TODAY.plusDays(10))));

        service.activate(ADMIN, new SubscriptionPlanResponse(2, "Pro", null, 1L, 1L, 40, 8, true, null, false,
                List.of(), null, null), BillingCycle.MONTHLY);

        verify(subscriptions).renew(1, TODAY.plusDays(10).plusMonths(1), 40, 8, NOW);
    }

    @Test
    void switchingFromAPaidPlanQueuesTheNewPlanAfterItSoNoPaidDayIsLostOrOverlapped() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(record(1, 3, TODAY.minusDays(20), TODAY.plusDays(10))));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.YEARLY);

        LocalDate start = TODAY.plusDays(11);
        verify(subscriptions).insert(ADMIN, 2, 5, 1, start, start.plusYears(1), false, NOW);
        verify(subscriptions, never()).end(anyLong(), any(), any());
        verify(subscriptions, never()).softDelete(anyLong(), any());
        verify(subscriptions, never()).renew(anyLong(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void aNewPeriodQueuesBehindTheLastPaidOneAndExtendsItWhenItIsTheSamePlan() {
        var queued = record(2, 2, TODAY.plusDays(11), TODAY.plusDays(11).plusMonths(1));
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(
                record(1, 3, TODAY.minusDays(20), TODAY.plusDays(10)), queued));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);
        verify(subscriptions).renew(2, queued.endDate().plusMonths(1), 5, 1, NOW);

        service.activate(ADMIN, plan(3, "Basic"), BillingCycle.MONTHLY);
        LocalDate start = queued.endDate().plusDays(1);
        verify(subscriptions).insert(ADMIN, 3, 5, 1, start, start.plusMonths(1), false, NOW);
    }

    @Test
    void buyingReplacesAStarterThatRanBeforeTodayByEndingItYesterday() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(starter(1, 3, TODAY.minusDays(20))));

        service.activate(ADMIN, plan(3, "Basic"), BillingCycle.MONTHLY);

        verify(subscriptions).end(1, TODAY.minusDays(1), NOW);
        verify(subscriptions).insert(ADMIN, 3, 5, 1, TODAY, TODAY.plusMonths(1), false, NOW);
        verify(subscriptions, never()).renew(anyLong(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void aStarterThatOnlyStartedTodayIsWithdrawnSoNoEndDateFallsBeforeItsStart() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of(starter(1, 3, TODAY)));

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);

        verify(subscriptions).softDelete(1, NOW);
        verify(subscriptions, never()).end(anyLong(), any(), any());
        verify(subscriptions).insert(ADMIN, 2, 5, 1, TODAY, TODAY.plusMonths(1), false, NOW);
    }

    @Test
    void anOwnerWhoseSubscriptionLapsedStartsFreshFromToday() {
        when(subscriptions.findNotEnded(ADMIN, TODAY)).thenReturn(List.of());

        service.activate(ADMIN, plan(2, "Pro"), BillingCycle.MONTHLY);

        verify(subscriptions).insert(ADMIN, 2, 5, 1, TODAY, TODAY.plusMonths(1), false, NOW);
        verify(subscriptions, never()).end(anyLong(), any(), any());
    }

    static SubscriptionRecord record(long id, long planId, LocalDate start, LocalDate end) {
        return new SubscriptionRecord(id, ADMIN, planId, 5, 1, start, end, false);
    }

    static SubscriptionRecord starter(long id, long planId, LocalDate start) {
        return new SubscriptionRecord(id, ADMIN, planId, 5, 1, start, start.plusYears(1), true);
    }

    static SubscriptionPlanResponse plan(long id, String name) {
        return new SubscriptionPlanResponse(id, name, null, 299_000L, 2_990_000L, 5, 1, true, null, false,
                List.of(), OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
