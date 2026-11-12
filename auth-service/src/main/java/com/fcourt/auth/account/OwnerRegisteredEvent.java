package com.fcourt.auth.account;

import java.time.Instant;
import java.util.List;

/**
 * Data of the {@code OwnerRegisteredEvent} published on {@code fcourt.auth.events} (key = userId) when a court
 * owner registers and waits for approval. {@code superAdminIds} are the active super admins at that moment, so
 * notification-service can address its in-app notification by user id without asking this service back.
 */
public record OwnerRegisteredEvent(long userId, String email, String fullName, Instant registeredAt,
                                   List<Long> superAdminIds) {
    public static final String TYPE = "OwnerRegisteredEvent";
    public static final int VERSION = 1;
}
