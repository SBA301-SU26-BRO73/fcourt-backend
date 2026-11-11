package com.fcourt.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcourt.notification.device.UserDeviceRepository;
import com.fcourt.notification.event.EventEnvelope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sns.model.SnsException;

class PushDeliveryServiceTest {
    private static final String EVENT = """
            {"eventId":"8fb08674-9f9b-4d2d-9cf5-000000000001","type":"PaymentConfirmedEvent",
             "payment":{"bookingId":42,"customerId":7}}""";
    static class FakeSender implements PushSender {
        int sends; String endpoint; PushMessage message; RuntimeException failure;
        public void send(String endpointArn, PushMessage payload) {
            if (failure != null) throw failure;
            sends++; endpoint=endpointArn; message=payload;
        }
    }
    private PushDeliveryService service(UserDeviceRepository devices, JdbcTemplate jdbc, FakeSender sender) {
        var deviceDelivery = new PushDeviceDelivery(jdbc, devices, sender);
        return new PushDeliveryService(new PushPlanner(), devices, deviceDelivery);
    }
    private EventEnvelope event() { return EventEnvelope.parse(new ObjectMapper(), EVENT); }
    @Test void usersWithoutDevicesReceiveNothing() {
        var devices=mock(UserDeviceRepository.class); var jdbc=mock(JdbcTemplate.class); var sender=new FakeSender();
        when(devices.activeDevices(7)).thenReturn(List.of());
        service(devices,jdbc,sender).send(event());
        assertThat(sender.sends).isZero();
    }
    @Test void sendsOnePayloadToEveryActiveDevice() {
        var devices=mock(UserDeviceRepository.class); var jdbc=mock(JdbcTemplate.class); var sender=new FakeSender();
        when(devices.activeDevices(7)).thenReturn(List.of(new UserDeviceRepository.ActiveDevice(1,"phone","arn:1"),
                new UserDeviceRepository.ActiveDevice(2,"tablet","arn:2")));
        when(jdbc.update(anyString(), any(UUID.class), anyLong())).thenReturn(1);
        service(devices,jdbc,sender).send(event());
        assertThat(sender.sends).isEqualTo(2);
        assertThat(sender.message.data()).containsEntry("type","BOOKING_CONFIRMED")
                .containsEntry("bookingId","42").containsEntry("route","/bookings/42");
    }
    @Test void skipsAlreadyRecordedEventAndDeviceOnRedelivery() {
        var devices=mock(UserDeviceRepository.class); var jdbc=mock(JdbcTemplate.class); var sender=new FakeSender();
        when(devices.activeDevices(7)).thenReturn(List.of(new UserDeviceRepository.ActiveDevice(1,"phone","arn:1")));
        when(jdbc.update(anyString(), any(UUID.class), anyLong())).thenReturn(1, 1, 0);
        var delivery=service(devices,jdbc,sender);
        delivery.send(event()); delivery.send(event());
        assertThat(sender.sends).isEqualTo(1);
    }
    @Test void endpointDisabledDisablesDeviceWithoutThrowing() {
        var devices=mock(UserDeviceRepository.class); var jdbc=mock(JdbcTemplate.class); var sender=new FakeSender();
        when(devices.activeDevices(7)).thenReturn(List.of(new UserDeviceRepository.ActiveDevice(1,"phone","arn:1")));
        when(jdbc.update(anyString(), any(UUID.class), anyLong())).thenReturn(1);
        sender.failure=SnsException.builder().awsErrorDetails(AwsErrorDetails.builder().errorCode("EndpointDisabled").build()).build();
        service(devices,jdbc,sender).send(event());
        verify(devices).disable(7,"phone");
    }
    @Test void temporarySnsFailurePropagatesForKafkaRetry() {
        var devices=mock(UserDeviceRepository.class); var jdbc=mock(JdbcTemplate.class); var sender=new FakeSender();
        when(devices.activeDevices(7)).thenReturn(List.of(new UserDeviceRepository.ActiveDevice(1,"phone","arn:1")));
        when(jdbc.update(anyString(), any(UUID.class), anyLong())).thenReturn(1);
        sender.failure=SnsException.builder().awsErrorDetails(AwsErrorDetails.builder().errorCode("Throttled").build()).build();
        assertThatThrownBy(() -> service(devices,jdbc,sender).send(event())).isSameAs(sender.failure);
    }
}
