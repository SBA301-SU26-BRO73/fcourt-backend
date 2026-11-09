package com.fcourt.voucher;

import com.fcourt.voucher.config.FacilityResilienceConfig;
import com.fcourt.voucher.config.FeignClientsConfig;
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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** Once facility keeps failing the circuit opens and the fallback answers 503 without calling facility. */
@SpringBootTest(classes = VoucherAuthorityCircuitBreakerTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"voucher.facility.resilience.retry-max-attempts=2",
                "voucher.facility.resilience.retry-wait=10ms",
                "voucher.facility.resilience.sliding-window-size=4",
                "voucher.facility.resilience.minimum-number-of-calls=4",
                "voucher.facility.resilience.failure-rate-threshold=50",
                "voucher.facility.resilience.open-state-wait=1h"})
class VoucherAuthorityCircuitBreakerTest {

    @RegisterExtension
    static WireMockExtension facility = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void facilityUrl(DynamicPropertyRegistry registry) {
        registry.add("voucher.facility-url", facility::baseUrl);
    }

    @Autowired
    VoucherAuthority authority;

    @Test
    void opensAfterRepeatedFailuresAndFallsBackWithoutCallingFacility() {
        Jwt admin = Jwt.withTokenValue("tok").header("alg", "none").subject("100")
                .claim("roles", List.of("ADMIN")).build();
        facility.stubFor(get(urlEqualTo("/api/admin/branches")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authority.branches(admin)).isInstanceOf(ResponseStatusException.class);
        }
        // 4 logical calls x 2 attempts each reached facility.
        facility.verify(8, getRequestedFor(urlEqualTo("/api/admin/branches")));
        facility.resetRequests();

        assertThatThrownBy(() -> authority.branches(admin)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("Branch verification unavailable");
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
    @Import({FeignClientsConfig.class, FacilityResilienceConfig.class, VoucherAuthority.class})
    static class App {
    }
}
