package com.fcourt.notification.mail;

/** Delivers one email or throws {@link EmailDeliveryException}. */
public interface EmailSender {
    void send(OutgoingEmail email);
}
