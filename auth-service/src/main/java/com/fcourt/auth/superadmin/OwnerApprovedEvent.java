package com.fcourt.auth.superadmin;

import java.time.Instant;

/**
 * Data of the {@code OwnerApprovedEvent} published on {@code fcourt.auth.events}
 * (key = userId). facility/subscription services use it to enable branch creation;
 * {@code email} and {@code fullName} let the notification service tell the owner.
 */
public record OwnerApprovedEvent(long userId, long approvedBy, Instant approvedAt, String email, String fullName) {
    public static final String TYPE = "OwnerApprovedEvent";
    public static final int VERSION = 1;
}
