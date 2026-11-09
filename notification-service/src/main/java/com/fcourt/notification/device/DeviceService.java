package com.fcourt.notification.device;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {
    private final UserDeviceRepository devices;
    private final PushEndpointGateway gateway;

    DeviceService(UserDeviceRepository devices, PushEndpointGateway gateway) {
        this.devices = devices;
        this.gateway = gateway;
    }

    @Transactional
    public void register(long userId, DeviceRegistration registration) {
        String endpointArn = gateway.register(registration.token(), userId);
        devices.upsert(userId, registration, endpointArn);
    }

    @Transactional
    public void unregister(long userId, String deviceId) {
        devices.endpointArn(userId, deviceId).ifPresent(gateway::delete);
        devices.delete(userId, deviceId);
    }
}
