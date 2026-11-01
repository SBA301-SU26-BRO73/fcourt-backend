package com.fcourt.subscription.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.subscription.common.ApiException;
import com.fcourt.subscription.common.PageResponse.Pagination;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanRequest;
import com.fcourt.subscription.plan.PlanDtos.SubscriptionPlanResponse;
import com.fcourt.subscription.plan.PlanRepository.PlanRecord;
import com.fcourt.subscription.plan.PlanRepository.PlanValues;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class PlanServiceTest {
    static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    PlanRepository plans;
    PlanService service;

    @BeforeEach
    void setUp() {
        plans = mock(PlanRepository.class);
        service = new PlanService(plans, Clock.fixed(NOW, ZoneOffset.UTC));
        when(plans.featuresOf(any())).thenReturn(Map.of());
    }

    @Test
    void listAttachesTheFeatureBulletsOfEveryPlanOnThePage() {
        when(plans.findPage(10, 0)).thenReturn(List.of(plan(1, "Basic", true), plan(2, "Pro", true)));
        when(plans.count()).thenReturn(2L);
        when(plans.featuresOf(List.of(1L, 2L))).thenReturn(Map.of(1L, List.of("1 branch"), 2L, List.of("5 branches", "Reports")));

        var page = service.list(Pagination.of(null, null));

        assertThat(page.content()).extracting(SubscriptionPlanResponse::features)
                .containsExactly(List.of("1 branch"), List.of("5 branches", "Reports"));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void aPlanWithoutFeaturesGetsAnEmptyListNotNull() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", true)));

        assertThat(service.get(1).features()).isEmpty();
    }

    @Test
    void listActiveReturnsThePlansOnSaleInTheOrderTheRepositoryGivesThem() {
        when(plans.findActive()).thenReturn(List.of(plan(3, "Cheap", true), plan(1, "Pricey", true)));

        assertThat(service.listActive()).extracting(SubscriptionPlanResponse::name).containsExactly("Cheap", "Pricey");
    }

    @Test
    void getOfAMissingPlanIsNotFound() {
        when(plans.find(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Subscription plan not found"));
    }

    @Test
    void createStoresTrimmedValuesDefaultsAndCleanedFeatures() {
        when(plans.insert(any(), any())).thenReturn(5L);
        when(plans.find(5)).thenReturn(Optional.of(plan(5, "Pro", true)));

        service.create(request("  Pro ", Arrays.asList(" Reports ", "", null, "Reports", "Support  ")));

        var values = ArgumentCaptor.forClass(PlanValues.class);
        verify(plans).insert(values.capture(), org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(values.getValue()).isEqualTo(new PlanValues("Pro", null, 100_000L, 1_000_000L, 5, 2, true, null, false));
        verify(plans).replaceFeatures(5, List.of("Reports", "Support"));
    }

    @Test
    void createWithoutFeaturesStoresAnEmptyList() {
        when(plans.insert(any(), any())).thenReturn(5L);
        when(plans.find(5)).thenReturn(Optional.of(plan(5, "Pro", true)));

        service.create(request("Pro", null));

        verify(plans).replaceFeatures(5, List.of());
    }

    @Test
    void createHonoursPopularAndInactiveFlags() {
        when(plans.insert(any(), any())).thenReturn(5L);
        when(plans.find(5)).thenReturn(Optional.of(plan(5, "Pro", false)));

        service.create(new SubscriptionPlanRequest("Pro", " Best value ", null, 900_000L, 3, 10, null, "#fff", true, false));

        var values = ArgumentCaptor.forClass(PlanValues.class);
        verify(plans).insert(values.capture(), any());
        assertThat(values.getValue()).isEqualTo(new PlanValues("Pro", "Best value", null, 900_000L, 10, 3, false, "#fff", true));
    }

    @Test
    void createRejectsANameAlreadyInUseAndWritesNothing() {
        when(plans.nameInUse("Pro", 0)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Pro", null)))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Subscription plan name already exists"));
        verify(plans, never()).insert(any(), any());
        verify(plans, never()).replaceFeatures(anyLong(), any());
    }

    @Test
    void createTurnsALostRaceOnTheUniqueIndexIntoAConflict() {
        when(plans.insert(any(), any())).thenThrow(new DuplicateKeyException("uq_subscription_plans_name_active"));

        assertThatThrownBy(() -> service.create(request("Pro", null)))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Subscription plan name already exists"));
        verify(plans, never()).replaceFeatures(anyLong(), any());
    }

    @Test
    void updateKeepsTheCurrentActiveFlagWhenTheRequestOmitsIt() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", false)));

        service.update(1, request("Basic", List.of("One")));

        var values = ArgumentCaptor.forClass(PlanValues.class);
        verify(plans).nameInUse("Basic", 1);
        verify(plans).update(org.mockito.ArgumentMatchers.eq(1L), values.capture(), org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(values.getValue().active()).isFalse();
        verify(plans).replaceFeatures(1, List.of("One"));
    }

    @Test
    void updateCanReactivateAPlanExplicitly() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", false)));

        service.update(1, new SubscriptionPlanRequest("Basic", null, 1L, 1L, 1, 1, null, null, null, true));

        var values = ArgumentCaptor.forClass(PlanValues.class);
        verify(plans).update(anyLong(), values.capture(), any());
        assertThat(values.getValue().active()).isTrue();
    }

    @Test
    void updateRejectsANameTakenByAnotherPlan() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", true)));
        when(plans.nameInUse("Pro", 1)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1, request("Pro", null)))
                .satisfies(error -> assertApi(error, HttpStatus.CONFLICT, "Subscription plan name already exists"));
        verify(plans, never()).update(anyLong(), any(), any());
        verify(plans, never()).replaceFeatures(anyLong(), any());
    }

    @Test
    void updateOfAMissingPlanIsNotFound() {
        when(plans.find(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9, request("Pro", null)))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Subscription plan not found"));
    }

    @Test
    void deleteIsASoftDeleteAndAMissingPlanIsNotFound() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", true)));
        when(plans.find(9)).thenReturn(Optional.empty());

        service.delete(1);

        verify(plans).softDelete(1, NOW);
        assertThatThrownBy(() -> service.delete(9))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Subscription plan not found"));
        verify(plans, never()).softDelete(org.mockito.ArgumentMatchers.eq(9L), any());
    }

    @Test
    void findOnSaleReturnsAnActivePlanAndHidesInactiveOrMissingOnes() {
        when(plans.find(1)).thenReturn(Optional.of(plan(1, "Basic", true)));
        when(plans.find(2)).thenReturn(Optional.of(plan(2, "Retired", false)));
        when(plans.find(3)).thenReturn(Optional.empty());

        assertThat(service.findOnSale(1)).get().extracting(SubscriptionPlanResponse::name).isEqualTo("Basic");
        assertThat(service.findOnSale(2)).isEmpty();
        assertThat(service.findOnSale(3)).isEmpty();
    }

    @Test
    void cheapestOnSaleIsTheFirstPlanTheRepositoryListsAsActive() {
        when(plans.findActive()).thenReturn(List.of(plan(3, "Cheap", true), plan(1, "Pricey", true)));

        assertThat(service.cheapestOnSale()).get().extracting(SubscriptionPlanResponse::name).isEqualTo("Cheap");
    }

    @Test
    void cheapestOnSaleIsEmptyWhenNothingIsOnSale() {
        when(plans.findActive()).thenReturn(List.of());

        assertThat(service.cheapestOnSale()).isEmpty();
    }

    @Test
    void describeStillWorksForAPlanThatWasDeletedAfterItWasBought() {
        when(plans.findIncludingDeleted(5)).thenReturn(Optional.of(plan(5, "Old plan", false)));
        when(plans.findIncludingDeleted(9)).thenReturn(Optional.empty());

        assertThat(service.describe(5).name()).isEqualTo("Old plan");
        assertThatThrownBy(() -> service.describe(9))
                .satisfies(error -> assertApi(error, HttpStatus.NOT_FOUND, "Subscription plan not found"));
    }

    static void assertApi(Throwable error, HttpStatus status, String detail) {
        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.status()).isEqualTo(status);
            assertThat(api.getMessage()).isEqualTo(detail);
        });
    }

    static SubscriptionPlanRequest request(String name, List<String> features) {
        return new SubscriptionPlanRequest(name, null, 100_000L, 1_000_000L, 2, 5, features, null, null, null);
    }

    static PlanRecord plan(long id, String name, boolean active) {
        return new PlanRecord(id, name, null, 100_000L, 1_000_000L, 5, 2, active, null, false,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }
}
