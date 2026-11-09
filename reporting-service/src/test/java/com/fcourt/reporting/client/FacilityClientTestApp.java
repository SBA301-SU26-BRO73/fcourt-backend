package com.fcourt.reporting.client;

import com.fcourt.reporting.config.FacilityResilienceConfig;
import com.fcourt.reporting.config.FeignClientsConfig;
import com.fcourt.reporting.integration.FacilityClient;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Not component-scanned by the application (TestConfiguration). Minimal context for the facility client tests: Feign, Resilience4J and Jackson, no database, Kafka or security. */
@TestConfiguration
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
class FacilityClientTestApp {
}
