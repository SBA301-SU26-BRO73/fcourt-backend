package com.fcourt.reporting.client;

import com.fcourt.reporting.config.FacilityResilienceConfig;
import com.fcourt.reporting.config.FeignClientsConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.reporting.integration.FacilityClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Once facility keeps failing the circuit opens and the fallback answers 503 without calling facility. */
@SpringBootTest(classes = FacilityCircuitBreakerTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"reporting.facility.resilience.retry-max-attempts=2",
                "reporting.facility.resilience.retry-wait=10ms",
                "reporting.facility.resilience.sliding-window-size=4",
                "reporting.facility.resilience.minimum-number-of-calls=4",
                "reporting.facility.resilience.failure-rate-threshold=50",
                "reporting.facility.resilience.open-state-wait=1h"})
class FacilityCircuitBreakerTest {

    @RegisterExtension
    static WireMockExtension facility = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void facilityUrl(DynamicPropertyRegistry registry) {
        registry.add("reporting.facility-url", facility::baseUrl);
    }

    @Autowired
    FacilityClient client;

    @Test
    void opensAfterRepeatedFailuresAndFallsBackWithoutCallingFacility() {
        facility.stubFor(get(urlEqualTo("/api/admin/branches")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.allBranches("tok")).isInstanceOf(ResponseStatusException.class);
        }
        // 4 logical calls x 2 attempts each reached facility.
        facility.verify(8, getRequestedFor(urlEqualTo("/api/admin/branches")));
        facility.resetRequests();

        assertThatThrownBy(() -> client.allBranches("tok")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("Facility service unavailable");
                });
        facility.verify(0, getRequestedFor(urlEqualTo("/api/admin/branches")));
    }

    /** Nested so component scanning of the application never picks it up. Feign + Resilience4J + Jackson only. */
    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"})
    @Import({FeignClientsConfig.class, FacilityResilienceConfig.class, FacilityClient.class})
    static class App {
    }
}
