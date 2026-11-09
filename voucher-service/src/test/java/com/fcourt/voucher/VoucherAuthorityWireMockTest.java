package com.fcourt.voucher;

import com.fcourt.voucher.config.FacilityResilienceConfig;
import com.fcourt.voucher.config.FeignClientsConfig;
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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Success, retry and error mapping of the OpenFeign call to facility-service (WireMock stands in for it). */
@SpringBootTest(classes = VoucherAuthorityWireMockTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"voucher.facility.resilience.retry-max-attempts=3",
                "voucher.facility.resilience.retry-wait=10ms"})
class VoucherAuthorityWireMockTest {

    @RegisterExtension
    static WireMockExtension facility = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void facilityUrl(DynamicPropertyRegistry registry) {
        registry.add("voucher.facility-url", facility::baseUrl);
    }

    static final String URL = "/api/admin/branches";
    static final String BRANCHES = "[{\"id\":7,\"adminId\":100,\"name\":\"ignored\"}]";

    @Autowired
    VoucherAuthority authority;

    @AfterEach
    void reset() {
        facility.resetAll();
    }

    static Jwt admin() {
        return Jwt.withTokenValue("tok").header("alg", "none").subject("100").claim("roles", List.of("ADMIN")).build();
    }

    @Test
    void forwardsBearerTokenAndReturnsOwnedBranchIds() {
        facility.stubFor(get(urlEqualTo(URL)).withHeader("Authorization", equalTo("Bearer tok"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(BRANCHES)));

        assertThat(authority.branches(admin())).containsExactly(7L);
        facility.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        facility.stubFor(get(urlEqualTo(URL)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        facility.stubFor(get(urlEqualTo(URL)).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("third"));
        facility.stubFor(get(urlEqualTo(URL)).inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(BRANCHES)));

        assertThat(authority.branches(admin())).containsExactly(7L);
        facility.verify(3, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void givesUpWithServiceUnavailableAfterAllAttempts() {
        facility.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> authority.branches(admin())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("Branch verification unavailable");
                });
        facility.verify(3, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void forbiddenFromFacilityIsForbiddenWithoutRetry() {
        facility.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> authority.branches(admin())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        facility.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void foreignBranchRowIsRejected() {
        facility.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":7,\"adminId\":999}]")));

        assertThatThrownBy(() -> authority.branches(admin())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
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
    @Import({FeignClientsConfig.class, FacilityResilienceConfig.class, VoucherAuthority.class})
    static class App {
    }
}
