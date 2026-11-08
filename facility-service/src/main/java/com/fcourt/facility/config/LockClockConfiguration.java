package com.fcourt.facility.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Venue clock: template times and lock windows are local to Asia/Ho_Chi_Minh. */
@Configuration
class LockClockConfiguration {
    @Bean
    Clock venueClock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
