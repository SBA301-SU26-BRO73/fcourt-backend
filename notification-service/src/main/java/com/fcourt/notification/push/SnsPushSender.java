package com.fcourt.notification.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@Component
@ConditionalOnExpression("! '${notification.push.platform-application-arn:}'.isEmpty()")
final class SnsPushSender implements PushSender {
    private final SnsClient sns;
    private final ObjectMapper json;

    SnsPushSender(SnsClient sns, ObjectMapper json) { this.sns = sns; this.json = json; }

    @Override
    public void send(String endpointArn, PushMessage message) {
        try {
            String fcm = json.writeValueAsString(Map.of(
                    "notification", Map.of("title", message.title(), "body", message.body()),
                    "data", message.data()));
            sns.publish(PublishRequest.builder().targetArn(endpointArn).messageStructure("json")
                    .message(json.writeValueAsString(Map.of("default", message.body(), "GCM", fcm))).build());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode push payload", error);
        }
    }
}
