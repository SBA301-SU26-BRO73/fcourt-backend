package com.fcourt.reporting.config;

import com.fcourt.reporting.client.FacilityFeignClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/** Enables the {@code @FeignClient} interfaces in the {@code client} package. */
@Configuration
@EnableFeignClients(basePackageClasses = FacilityFeignClient.class)
public class FeignClientsConfig {
}
