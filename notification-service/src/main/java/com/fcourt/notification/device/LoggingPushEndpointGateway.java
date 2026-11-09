package com.fcourt.notification.device;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local fallback used when SNS is not configured. */
@Configuration
class LoggingPushEndpointGateway {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushEndpointGateway.class);

    @Bean
    @ConditionalOnMissingBean(PushEndpointGateway.class)
    @ConditionalOnExpression("'${notification.push.platform-application-arn:}'.isEmpty()")
    PushEndpointGateway loggingPushEndpointGateway() {
        return new PushEndpointGateway() {
            @Override
            public String register(String token, long userId) {
                log.info("Push endpoint registration skipped because SNS is not configured for user {}", userId);
                return "local:" + UUID.randomUUID();
            }

            @Override
            public void delete(String endpointArn) {
                log.info("Push endpoint deletion skipped because SNS is not configured");
            }
        };
    }
}
