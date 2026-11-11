package com.fcourt.notification.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

@Configuration
@ConditionalOnProperty(name = "notification.push.platform-application-arn")
@ConditionalOnExpression("! '${notification.push.platform-application-arn:}'.isEmpty()")
class SnsConfiguration {
    @Bean(destroyMethod = "close")
    SnsClient snsClient(@Value("${notification.push.region}") String region) {
        return SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
