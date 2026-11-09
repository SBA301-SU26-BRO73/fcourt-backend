package com.fcourt.notification.push;

public interface PushSender {
    void send(String endpointArn, PushMessage message);
}
