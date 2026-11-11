package com.fcourt.reporting.client;

import com.fcourt.reporting.config.FacilityResilienceConfig;
import com.fcourt.reporting.config.FeignClientsConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.reporting.integration.FacilityClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Success, retry and error mapping of the OpenFeign call to facility-service (WireMock stands in for it). */
@SpringBootTest(classes = FacilityClientWireMockTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"reporting.facility.resilience.retry-max-attempts=3",
                "reporting.facility.resilience.retry-wait=10ms"})
class FacilityClientWireMockTest {

    @RegisterExtension
    static WireMockExtension facility = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void facilityUrl(DynamicPropertyRegistry registry) {
        registry.add("reporting.facility-url", facility::baseUrl);
    }

    static final String BRANCH = "{\"id\":5,\"adminId\":7,\"status\":\"ACTIVE\",\"openTime\":\"06:00:00\","
            + "\"closeTime\":\"22:00:00\",\"courtCount\":4,\"extra\":\"x\"}";

    @Autowired
    FacilityClient client;

    @AfterEach
    void reset() {
        facility.resetAll();
    }

    @Test
    void forwardsBearerTokenAndMapsBranch() {
        facility.stubFor(get(urlEqualTo("/api/admin/branches/5")).withHeader("Authorization", equalTo("Bearer tok"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(BRANCH)));

        FacilityClient.OwnedBranch branch = client.ownedBranch(5, 7, "tok");

        assertThat(branch.id()).isEqualTo(5L);
        assertThat(branch.courtCount()).isEqualTo(4);
        facility.verify(1, getRequestedFor(urlEqualTo("/api/admin/branches/5")));
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        String url = "/api/admin/branches";
        facility.stubFor(get(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        facility.stubFor(get(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("third"));
        facility.stubFor(get(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[" + BRANCH + "]")));

        assertThat(client.ownedBranches(7, "tok")).hasSize(1);

        facility.verify(3, getRequestedFor(urlEqualTo(url)));
    }

    @Test
    void givesUpWithServiceUnavailableAfterAllAttempts() {
        facility.stubFor(get(urlEqualTo("/api/admin/branches")).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> client.allBranches("tok")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("Facility service unavailable");
                });
        facility.verify(3, getRequestedFor(urlEqualTo("/api/admin/branches")));
    }

    @Test
    void forbiddenIsDeniedWithoutRetry() {
        facility.stubFor(get(urlEqualTo("/api/admin/branches/5/courts")).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.courts(5, "tok")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getReason()).isEqualTo("Branch access denied");
                });
        facility.verify(1, getRequestedFor(urlEqualTo("/api/admin/branches/5/courts")));
    }

    @Test
    void foreignOwnerIsDenied() {
        facility.stubFor(get(urlEqualTo("/api/admin/branches/5")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(BRANCH)));

        assertThatThrownBy(() -> client.ownedBranch(5, 99, "tok")).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
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
