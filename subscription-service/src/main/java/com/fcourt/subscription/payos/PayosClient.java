package com.fcourt.subscription.payos;

import java.time.Instant;

/** Creates PayOS payment links. A seam so billing logic can be tested without the network. */
public interface PayosClient {
    PaymentLink createLink(PaymentLinkRequest request);

    /** {@code amount} is a whole number of VND; {@code description} is at most 25 characters. */
    record PaymentLinkRequest(long orderCode, long amount, String description, String itemName, Instant expiresAt) {
    }

    record PaymentLink(String paymentLinkId, String checkoutUrl, String qrCode) {
    }

    /** The gateway refused the request or could not be reached. The message never carries credentials. */
    class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message, Throwable cause) {
            super(message, cause);
        }

        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
