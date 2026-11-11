package com.fcourt.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties={"booking.events-enabled=false","booking.integration-token=integration"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext
class OpenApiDocsTest {
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("booking_db");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired MockMvc mvc;

    @Test
    void apiDocsArePublicAndHideInternalEndpoints() throws Exception {
        var body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"openapi\"").contains("bearer-jwt").doesNotContain("/api/internal");
    }

    @Test
    void swaggerUiIsNotBlockedBySecurity() throws Exception {
        int status = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void businessEndpointStillRequiresToken() throws Exception {
        mvc.perform(get("/api/bookings/my")).andExpect(status().isUnauthorized());
    }
}
