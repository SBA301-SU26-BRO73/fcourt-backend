package com.fcourt.facility.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Opt-in (facility.realtime.enabled=true): point spring.data.redis.* at the Redis used by slot-hold-engine. */
@Configuration
@ConditionalOnProperty(name = "facility.realtime.enabled", havingValue = "true")
class RealtimeConfiguration {
    @Bean
    LockScheduleEvents lockScheduleEvents(StringRedisTemplate redis, ObjectMapper json) {
        return new RedisLockScheduleEvents(redis, json);
    }
}
