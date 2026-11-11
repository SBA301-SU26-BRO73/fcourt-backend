package com.fcourt.notification.event;

import java.util.UUID;

/**
 * Business keys of real-world facts, shared by the email idempotency ({@code processed_events.business_key})
 * and the in-app notifications ({@code in_app_notifications.dedupe_key}), so both channels agree on what counts
 * as "the same notification".
 */
public final class BusinessKeys {
    private BusinessKeys() {
    }

    public static String bookingConfirmed(long bookingId) {
        return "booking-confirmed:" + bookingId;
    }

    /** Customer cancellation, payment expiry, owner cancellation and payment rejection all end the same booking. */
    public static String bookingCancelled(long bookingId) {
        return "booking-cancelled:" + bookingId;
    }

    public static String ownerApproved(long userId, String approvedAt) {
        return "owner-approved:" + userId + ":" + approvedAt;
    }

    public static String ownerApprovedByEvent(long userId, UUID eventId) {
        return "owner-approved:" + userId + ":" + eventId;
    }

    public static String ownerRegistered(long userId) {
        return "owner-registered:" + userId;
    }

    public static String paymentSubmitted(UUID eventId) {
        return "payment-submitted:" + eventId;
    }

    public static String refundRequested(long refundId) {
        return "refund-requested:" + refundId;
    }

    public static String refundCompleted(long refundId) {
        return "refund-completed:" + refundId;
    }
}
