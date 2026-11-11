package com.fcourt.subscription.billing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.subscription.billing.SubscriptionRepository.SubscriptionRecord;
import com.fcourt.subscription.common.ApiExceptionHandler;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Plan limits endpoint used by facility-service: live plan, free defaults, and owner scoping. */
class InternalLimitsControllerTest {
    static final String PATH = "/api/internal/subscriptions/admins/{id}/limits";
    SubscriptionService subscriptions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        subscriptions = mock(SubscriptionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InternalLimitsController(subscriptions, 3, 15))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void liveSubscriptionLimitsAreReturned() throws Exception {
        when(subscriptions.liveLimits(7)).thenReturn(Optional.of(new SubscriptionRecord(1, 7, 2, 40, 6,
                LocalDate.of(2026, 11, 1), LocalDate.of(2027, 11, 1), false)));

        mvc.perform(get(PATH, 7).principal(BillingControllerTest.token(7, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBranches").value(6))
                .andExpect(jsonPath("$.maxCourts").value(40));
    }

    @Test
    void ownerWithoutALiveSubscriptionGetsTheFreeDefaults() throws Exception {
        when(subscriptions.liveLimits(7)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, 7).principal(BillingControllerTest.token(7, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBranches").value(3))
                .andExpect(jsonPath("$.maxCourts").value(15));
    }

    @Test
    void anOwnerCannotReadAnotherOwnersLimits() throws Exception {
        mvc.perform(get(PATH, 8).principal(BillingControllerTest.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminMayReadAnyOwner() throws Exception {
        when(subscriptions.liveLimits(8)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, 8).principal(BillingControllerTest.token(1, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCourts").value(15));
    }
}
