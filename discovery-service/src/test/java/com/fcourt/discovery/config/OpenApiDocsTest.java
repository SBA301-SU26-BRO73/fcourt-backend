package com.fcourt.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.discovery.controller.BranchController;
import com.fcourt.discovery.controller.CourtSlotInternalController;
import com.fcourt.discovery.service.BranchSearchService;
import com.fcourt.discovery.service.CourtSlotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI docs are public, hide internal endpoints and leave the rest of the security chain untouched.
 * A web slice with the real security chain and springdoc auto-configuration: the full context would need
 * Postgres, Kafka and Redis for no extra coverage of this feature.
 */
@WebMvcTest({BranchController.class, CourtSlotInternalController.class})
@Import({DiscoverySecurity.class, OpenApiConfig.class})
@ImportAutoConfiguration({
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
        org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
        org.springdoc.webmvc.ui.SwaggerConfig.class,
        org.springdoc.core.properties.SwaggerUiConfigProperties.class,
        org.springdoc.core.properties.SwaggerUiOAuthProperties.class})
@TestPropertySource(properties = {
        "discovery.auth.jwk-set-uri=http://localhost:1/jwks.json",
        "discovery.auth.issuer=http://localhost:1",
        "discovery.auth.audience=fcourt",
        "discovery.internal.slot-token=0123456789abcdef0123456789abcdef",
        "springdoc.api-docs.path=/v3/api-docs",
        "springdoc.swagger-ui.path=/swagger-ui.html",
        "springdoc.paths-to-exclude=/api/internal/**,/error"})
class OpenApiDocsTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourtSlotService slots;
    @MockitoBean BranchSearchService branches;

    @Test
    void apiDocsArePublicDescribeBearerJwtAndHideInternalEndpoints() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("/api/public/branches").doesNotContain("/api/internal");
    }

    @Test
    void swaggerUiIsNotBlockedBySecurity() throws Exception {
        int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void internalEndpointsStillRequireTheirToken() throws Exception {
        mvc.perform(get("/api/internal/courts/1/slot-conflicts?date=2030-01-01")).andExpect(status().isUnauthorized());
    }
}
