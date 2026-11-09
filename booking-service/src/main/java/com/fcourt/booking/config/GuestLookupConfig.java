package com.fcourt.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GuestLookupProperties.class)
public class GuestLookupConfig {}
