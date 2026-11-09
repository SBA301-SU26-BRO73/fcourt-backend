package com.fcourt.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.reporting.config.OpenApiConfig;
import com.fcourt.reporting.service.DashboardService;
import com.fcourt.reporting.service.RevenueExportService;
import com.fcourt.reporting.service.SuperAdminOverviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real security chain + springdoc on a web slice (no database or Docker). A {@code @WebMvcTest} slice does not load
 * springdoc auto-configuration, so it is imported explicitly.
 */
@WebMvcTest({ExportController.class, SuperAdminDashboardController.class, DashboardController.class})
@ImportAutoConfiguration({
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
        org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
        org.springdoc.webmvc.ui.SwaggerConfig.class,
        org.springdoc.core.properties.SwaggerUiConfigProperties.class,
        org.springdoc.core.properties.SwaggerUiOAuthProperties.class})
@Import({OpenApiDocsTest.RealSecurity.class, OpenApiConfig.class})
class OpenApiDocsTest {
    @TestConfiguration
    @ComponentScan(basePackages = "com.fcourt.reporting.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*ReportingSecurity"))
    static class RealSecurity { }

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RevenueExportService export;
    @MockitoBean SuperAdminOverviewService overview;
    @MockitoBean DashboardService dashboard;

    @Test
    void apiDocsArePublicDeclareBearerSchemeAndHideInternalEndpoints() throws Exception {
        var body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"openapi\"").contains("bearer-jwt").doesNotContain("/api/internal");
    }

    @Test
    void swaggerUiIsNotBlockedBySecurity() throws Exception {
        int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void businessEndpointStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/super-admin/dashboard/overview")).andExpect(status().isUnauthorized());
    }
}
