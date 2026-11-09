package com.fcourt.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.hold.config.OpenApiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice with all controllers (services mocked), so no Redis or Docker is needed. A {@code @WebMvcTest} slice does
 * not load springdoc auto-configuration, so it is imported explicitly. The service has no Spring Security.
 */
@WebMvcTest({HoldController.class, CounterHoldController.class, OnlineHoldController.class})
@ImportAutoConfiguration({
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
        org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration.class,
        org.springdoc.webmvc.ui.SwaggerConfig.class,
        org.springdoc.core.properties.SwaggerUiConfigProperties.class,
        org.springdoc.core.properties.SwaggerUiOAuthProperties.class})
@Import(OpenApiConfig.class)
class OpenApiDocsTest {
    @Autowired MockMvc mvc;
    @MockitoBean HoldService holdService;
    @MockitoBean CounterHolds counterHolds;
    @MockitoBean OnlineHolds onlineHolds;

    @Test
    void apiDocsArePublicDeclareBearerSchemeAndHideInternalEndpoints() throws Exception {
        var body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"openapi\"").contains("bearer-jwt")
                .contains("/api/bookings/hold-slots").doesNotContain("/api/internal");
    }

    @Test
    void swaggerUiIsReachableWithoutToken() throws Exception {
        int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }
}
