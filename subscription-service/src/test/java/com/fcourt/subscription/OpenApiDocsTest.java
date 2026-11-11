package com.fcourt.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI docs are public, hide internal endpoints and leave the rest of the security chain untouched. Needs Docker. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends IntegrationSupport {
    @Autowired MockMvc mvc;

    @Test
    void apiDocsArePublicDescribeBearerJwtAndHideInternalEndpoints() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("/api/internal");
    }

    @Test
    void swaggerUiIsNotBlockedBySecurity() throws Exception {
        int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void businessEndpointsStillRequireAToken() throws Exception {
        mvc.perform(get("/api/subscription-plans").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
