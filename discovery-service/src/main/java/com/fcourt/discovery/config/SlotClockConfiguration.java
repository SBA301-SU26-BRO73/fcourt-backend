package com.fcourt.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class SlotClockConfiguration {
    @Bean
    public Clock slotClock() {
        return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
    }
}
