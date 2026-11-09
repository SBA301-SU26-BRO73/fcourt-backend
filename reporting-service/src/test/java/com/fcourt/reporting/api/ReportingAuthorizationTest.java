package com.fcourt.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fcourt.reporting.service.DashboardService;
import com.fcourt.reporting.service.RevenueExportService;
import com.fcourt.reporting.service.RevenueExportService.ExportFile;
import com.fcourt.reporting.service.RevenueExportService.Format;
import com.fcourt.reporting.service.SuperAdminDetailsService;
import com.fcourt.reporting.service.SuperAdminOverviewService;
import com.fcourt.reporting.service.SuperAdminOverviewService.Branches;
import com.fcourt.reporting.service.SuperAdminOverviewService.Customers;
import com.fcourt.reporting.service.SuperAdminOverviewService.Overview;
import com.fcourt.reporting.service.SuperAdminOverviewService.Owners;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Real security filter chain + controllers; services mocked, no database or Docker needed. */
@WebMvcTest({ExportController.class, SuperAdminDashboardController.class, DashboardController.class})
@Import(ReportingAuthorizationTest.RealSecurity.class)
class ReportingAuthorizationTest {
    /** ReportingSecurity is package-private in another package, so it is picked up by a name filter. */
    @TestConfiguration
    @ComponentScan(basePackages = "com.fcourt.reporting.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*ReportingSecurity"))
    static class RealSecurity { }

    static final String EXPORT = "/api/admin/dashboard/export/revenue?fromDate=2026-10-01&toDate=2026-10-31";
    static final String OVERVIEW = "/api/super-admin/dashboard/overview";
    static final String DETAILS = "/api/super-admin/dashboard/details";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RevenueExportService export;
    @MockitoBean SuperAdminOverviewService overview;
    @MockitoBean SuperAdminDetailsService details;
    @MockitoBean DashboardService dashboard;

    static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject("7")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void ownerDownloadsWithAttachmentHeaders() throws Exception {
        when(export.revenue(eq(7L), any(), eq(null), eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31)),
                eq(Format.XLSX))).thenReturn(new ExportFile("bao-cao-doanh-thu_20261001_20261031.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output -> output.write(new byte[] {1, 2, 3})));

        var async = mvc.perform(get(EXPORT).with(as("ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bao-cao-doanh-thu_20261001_20261031.xlsx\""))
                .andExpect(request().asyncStarted())
                .andReturn();
        var result = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void exportIsForOwnersOnly() throws Exception {
        for (String role : new String[] {"SUPER_ADMIN", "STAFF", "CUSTOMER"}) {
            mvc.perform(get(EXPORT).with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(EXPORT)).andExpect(status().isUnauthorized());
        verifyNoInteractions(export);
    }

    @Test
    void exportRejectsMissingOrMalformedParameters() throws Exception {
        mvc.perform(get("/api/admin/dashboard/export/revenue?fromDate=2026-10-01").with(as("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/dashboard/export/revenue?fromDate=nope&toDate=2026-10-31").with(as("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(EXPORT + "&branchId=0").with(as("ADMIN"))).andExpect(status().isBadRequest());
        verifyNoInteractions(export);
    }

    @Test
    void superAdminReadsTheOverview() throws Exception {
        when(overview.overview(any(), eq(12))).thenReturn(new Overview(new Owners(3, 2, 1, 6), new Branches(4, 3), 10,
                new Customers(40, 35), new BigDecimal("5000000"), new BigDecimal("900000"), List.of(), List.of(),
                Instant.parse("2026-11-05T03:00:00Z")));

        mvc.perform(get(OVERVIEW).with(as("SUPER_ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.gmv").value(5000000)).andExpect(jsonPath("$.saasRevenue").value(900000))
                .andExpect(jsonPath("$.owners.pending").value(2)).andExpect(jsonPath("$.courts").value(10));
    }

    @Test
    void overviewIsForSuperAdminOnly() throws Exception {
        for (String role : new String[] {"ADMIN", "STAFF", "CUSTOMER"}) {
            mvc.perform(get(OVERVIEW).with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(OVERVIEW)).andExpect(status().isUnauthorized());
        verifyNoInteractions(overview);
    }

    @Test
    void superAdminReadsTheDetails() throws Exception {
        when(details.details(any())).thenReturn(new SuperAdminDetailsService.Details(List.of(), List.of(), List.of(),
                List.of(new SuperAdminDetailsService.PlanSales(2, "Pro", 3, 2, 1, new BigDecimal("1500000"))), 2, 1,
                Instant.parse("2026-11-05T03:00:00Z")));

        mvc.perform(get(DETAILS).with(as("SUPER_ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.planSales[0].planName").value("Pro"))
                .andExpect(jsonPath("$.planSales[0].revenue").value(1500000))
                .andExpect(jsonPath("$.activeSubscriptionCount").value(2));
    }

    @Test
    void detailsAreForSuperAdminOnly() throws Exception {
        for (String role : new String[] {"ADMIN", "STAFF", "CUSTOMER"}) {
            mvc.perform(get(DETAILS).with(as(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(DETAILS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(details);
    }
}
