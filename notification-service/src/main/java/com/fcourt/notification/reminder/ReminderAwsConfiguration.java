package com.fcourt.notification.reminder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name="notification.push.reminder.enabled", havingValue="true")
class ReminderAwsConfiguration {
    @Bean(destroyMethod="close")
    SchedulerClient schedulerClient(@Value("${notification.push.region:ap-southeast-1}") String region) {
        return SchedulerClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create()).build();
    }
    @Bean(destroyMethod="close")
    SqsClient reminderSqsClient(@Value("${notification.push.region:ap-southeast-1}") String region) {
        return SqsClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create()).build();
    }
}
