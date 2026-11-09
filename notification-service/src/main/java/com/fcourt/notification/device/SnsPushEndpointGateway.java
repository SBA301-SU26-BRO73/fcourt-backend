package com.fcourt.notification.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreatePlatformEndpointRequest;
import software.amazon.awssdk.services.sns.model.DeleteEndpointRequest;

@Component
@ConditionalOnProperty(name = "notification.push.platform-application-arn")
@ConditionalOnExpression("! '${notification.push.platform-application-arn:}'.isEmpty()")
final class SnsPushEndpointGateway implements PushEndpointGateway {
    private final SnsClient sns;
    private final String platformApplicationArn;

    SnsPushEndpointGateway(
            SnsClient sns,
            @Value("${notification.push.platform-application-arn}") String platformApplicationArn) {
        this.sns = sns;
        this.platformApplicationArn = platformApplicationArn;
    }

    @Override
    public String register(String token, long userId) {
        return sns.createPlatformEndpoint(
                        CreatePlatformEndpointRequest.builder()
                                .platformApplicationArn(platformApplicationArn)
                                .token(token)
                                .build())
                .endpointArn();
    }

    @Override
    public void delete(String endpointArn) {
        sns.deleteEndpoint(DeleteEndpointRequest.builder().endpointArn(endpointArn).build());
    }
}
