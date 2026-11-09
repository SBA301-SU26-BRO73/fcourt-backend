package com.fcourt.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LoggingPushSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Bean
    @ConditionalOnMissingBean(PushSender.class)
    @ConditionalOnExpression("'${notification.push.platform-application-arn:}'.isEmpty()")
    PushSender localPushSender() {
        return (endpoint, message) -> log.info("Push send skipped because SNS is not configured (endpoint {})", endpoint);
    }
}
