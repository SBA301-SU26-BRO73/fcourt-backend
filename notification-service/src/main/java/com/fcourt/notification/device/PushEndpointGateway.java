package com.fcourt.notification.device;

/** Creates and removes the push endpoint associated with a device token. */
public interface PushEndpointGateway {
    String register(String token, long userId);

    void delete(String endpointArn);
}
