package com.fcourt.notification.mail;

/** The mail server did not accept the message. Deliberately carries no recipient or body. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
