package com.fcourt.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fcourt.facility.integration.SlotAvailabilityClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/** The OpenAPI docs are public, hide internal endpoints and leave the rest of the security chain untouched. Needs Docker. */
@SpringBootTest(properties = {
        "facility.events.relay-delay-ms=200",
        "facility.events.topic-partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = "facility.branch-updated.v1")
class OpenApiDocsTest {
    /** Singleton container: lives until the JVM exits, after the cached Spring context is closed. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean SlotAvailabilityClient availability;
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
        mvc.perform(get("/api/admin/branches")).andExpect(status().isUnauthorized());
    }
}
