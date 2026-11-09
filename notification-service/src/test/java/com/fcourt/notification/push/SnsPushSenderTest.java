package com.fcourt.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

class SnsPushSenderTest {
    @Test void wrapsGcmJsonAsAStringAndIncludesNotificationAndRoutingData() throws Exception {
        SnsClient sns=mock(SnsClient.class);
        var sender=new SnsPushSender(sns, new ObjectMapper());
        sender.send("arn:device", new PushMessage("Đặt sân thành công", "Đã xác nhận.",
                Map.of("type","BOOKING_CONFIRMED","notificationId","event-42","bookingId","42","route","/bookings/42")));
        var request=org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertThat(request.getValue().messageStructure()).isEqualTo("json");
        assertThat(request.getValue().targetArn()).isEqualTo("arn:device");
        var outer=new ObjectMapper().readTree(request.getValue().message());
        assertThat(outer.path("GCM").isTextual()).isTrue();
        var gcm=new ObjectMapper().readTree(outer.path("GCM").asText());
        assertThat(gcm.path("notification").path("title").asText()).isEqualTo("Đặt sân thành công");
        assertThat(gcm.path("notification").path("body").asText()).isEqualTo("Đã xác nhận.");
        assertThat(gcm.path("data").path("route").asText()).isEqualTo("/bookings/42");
    }
}
