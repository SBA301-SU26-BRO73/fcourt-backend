package com.fcourt.auth.superadmin;

import java.time.Instant;

/**
 * Data of the {@code OwnerApprovedEvent} published on {@code fcourt.auth.events}
 * (key = userId). facility/subscription services use it to enable branch creation.
 */
public record OwnerApprovedEvent(long userId, long approvedBy, Instant approvedAt) {
    public static final String TYPE = "OwnerApprovedEvent";
    public static final int VERSION = 1;
}
