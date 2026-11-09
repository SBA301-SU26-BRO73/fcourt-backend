package com.fcourt.notification.device;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceServiceTest {
    private final UserDeviceRepository devices = mock(UserDeviceRepository.class);
    private final FakePushEndpointGateway gateway = new FakePushEndpointGateway();
    private final DeviceService service = new DeviceService(devices, gateway);

    @Test
    void registersTheEndpointAndUpsertsTheDevice() {
        var registration = new DeviceRegistration("pixel-1", "ANDROID", "fcm-token");

        service.register(42L, registration);

        verify(devices).upsert(42L, registration, "fake-endpoint:42");
    }

    @Test
    void unregistersOnlyTheRequestedUserDeviceAndDeletesItsEndpoint() {
        when(devices.endpointArn(42L, "pixel-1")).thenReturn(Optional.of("arn:device:42"));

        service.unregister(42L, "pixel-1");

        assertThat(gateway.deletedEndpoint).isEqualTo("arn:device:42");
        verify(devices).delete(42L, "pixel-1");
    }

    private static class FakePushEndpointGateway implements PushEndpointGateway {
        private String deletedEndpoint;

        @Override
        public String register(String token, long userId) {
            return "fake-endpoint:" + userId;
        }

        @Override
        public void delete(String endpointArn) {
            deletedEndpoint = endpointArn;
        }
    }
}
