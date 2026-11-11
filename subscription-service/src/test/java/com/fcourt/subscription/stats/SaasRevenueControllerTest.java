package com.fcourt.subscription.stats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.subscription.config.SecurityConfiguration;
import com.fcourt.subscription.stats.SaasRevenueStats.MonthAmount;
import com.fcourt.subscription.stats.SaasRevenueStats.SaasRevenue;
import com.fcourt.subscription.stats.SubscriptionListStats.PlanRevenue;
import com.fcourt.subscription.stats.SubscriptionListStats.SubscriptionOverview;
import com.fcourt.subscription.stats.SubscriptionListStats.SubscriptionRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The real security filter chain in front of the SaaS revenue endpoint: SUPER_ADMIN only. */
@WebMvcTest(SaasRevenueController.class)
@Import(SecurityConfiguration.class)
class SaasRevenueControllerTest {
    static final String URL = "/api/super-admin/stats/saas-revenue?from=2026-09-01";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean SaasRevenueStats stats;
    @MockitoBean SubscriptionListStats subscriptions;

    @Test
    void superAdminReadsRevenue() throws Exception {
        when(stats.revenue(eq(LocalDate.of(2026, 9, 1)), eq("Asia/Ho_Chi_Minh"))).thenReturn(
                new SaasRevenue(new BigDecimal("1500000.00"), 3, List.of(new MonthAmount("2026-09", new BigDecimal("500000.00")))));

        mvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1500000.00))
                .andExpect(jsonPath("$.paidPayments").value(3))
                .andExpect(jsonPath("$.byMonth[0].month").value("2026-09"));
    }

    @Test
    void ownersAndCustomersAreForbidden() throws Exception {
        for (String role : new String[] {"ROLE_ADMIN", "ROLE_CUSTOMER", "ROLE_STAFF"}) {
            mvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(stats);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(stats);
    }

    @Test
    void missingFromIsABadRequest() throws Exception {
        mvc.perform(get("/api/super-admin/stats/saas-revenue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(stats);
    }

    @Test
    void superAdminListsSubscriptionsAndOthersAreForbidden() throws Exception {
        when(subscriptions.overview()).thenReturn(new SubscriptionOverview(
                List.of(new SubscriptionRow(1, 7, 2, "Pro", 10, 3, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                        false, new BigDecimal("500000.00"))),
                List.of(new PlanRevenue(2, "Pro", 1, new BigDecimal("500000.00")))));

        mvc.perform(get("/api/super-admin/stats/subscriptions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions[0].planName").value("Pro"))
                .andExpect(jsonPath("$.subscriptions[0].endDate").value("2026-02-01"))
                .andExpect(jsonPath("$.plans[0].revenue").value(500000.00));
        mvc.perform(get("/api/super-admin/stats/subscriptions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }
}
