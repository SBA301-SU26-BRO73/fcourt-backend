package com.fcourt.voucher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Resilience4J settings for calls to facility-service ({@code voucher.facility.resilience.*}). */
@ConfigurationProperties("voucher.facility.resilience")
public record FacilityResilienceProperties(
        Integer retryMaxAttempts,
        Duration retryWait,
        Integer slidingWindowSize,
        Integer minimumNumberOfCalls,
        Float failureRateThreshold,
        Duration openStateWait,
        Integer halfOpenCalls) {

    public FacilityResilienceProperties {
        retryMaxAttempts = retryMaxAttempts == null ? 3 : retryMaxAttempts;
        retryWait = retryWait == null ? Duration.ofMillis(200) : retryWait;
        slidingWindowSize = slidingWindowSize == null ? 10 : slidingWindowSize;
        minimumNumberOfCalls = minimumNumberOfCalls == null ? 5 : minimumNumberOfCalls;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        openStateWait = openStateWait == null ? Duration.ofSeconds(15) : openStateWait;
        halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
    }
}
