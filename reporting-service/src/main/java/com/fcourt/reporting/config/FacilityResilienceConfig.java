package com.fcourt.reporting.config;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retry and circuit breaker for facility-service. Only server-side failures (5xx, connection errors, timeouts) are
 * retried and counted by the breaker; 4xx answers (401/403/404) are business answers and neither retried nor counted.
 */
@Configuration
@EnableConfigurationProperties(FacilityResilienceProperties.class)
public class FacilityResilienceConfig {

    public static final String NAME = "facility-service";

    /** True for failures that say "facility is unhealthy": 5xx, or no response at all (status below 0). */
    public static boolean isServerFailure(Throwable error) {
        return error instanceof FeignException feign && (feign.status() < 0 || feign.status() >= 500);
    }

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> facilityCircuitBreakerCustomizer(FacilityResilienceProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumNumberOfCalls())
                .failureRateThreshold(props.failureRateThreshold())
                .waitDurationInOpenState(props.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(props.halfOpenCalls())
                .recordException(FacilityResilienceConfig::isServerFailure)
                .build();
        return factory -> factory.configure(builder -> builder.circuitBreakerConfig(config), NAME);
    }

    @Bean
    Retry facilityRetry(FacilityResilienceProperties props) {
        return Retry.of(NAME, RetryConfig.custom()
                .maxAttempts(props.retryMaxAttempts())
                .waitDuration(props.retryWait())
                .retryOnException(FacilityResilienceConfig::isServerFailure)
                .build());
    }
}
