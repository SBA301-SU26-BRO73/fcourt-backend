package com.fcourt.notification.reminder;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="notification.push.reminder.enabled", havingValue="false", matchIfMissing=true)
class LoggingReminderTrigger {
    private static final Logger log = LoggerFactory.getLogger(LoggingReminderTrigger.class);
    @Bean @ConditionalOnMissingBean(ReminderTrigger.class)
    ReminderTrigger noOpReminderTrigger() {
        return new ReminderTrigger() {
            public void create(long id, Instant fireAt) { log.debug("Push reminder scheduling disabled for booking {}", id); }
            public void delete(long id) { }
        };
    }
}
