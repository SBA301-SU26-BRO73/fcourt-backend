package com.fcourt.subscription.billing;

/**
 * A PayOS webhook payload that must not be trusted (bad signature, unknown order code, amount mismatch).
 * Kept apart from generic exceptions so a server fault is never mistaken for a bad payload and acknowledged.
 */
public class WebhookRejectedException extends RuntimeException {
    public WebhookRejectedException(String message) {
        super(message);
    }
}
