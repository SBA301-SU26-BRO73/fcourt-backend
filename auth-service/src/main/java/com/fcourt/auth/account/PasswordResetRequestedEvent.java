package com.fcourt.auth.account;

import java.time.Instant;

/**
 * Data of the {@code PasswordResetRequestedEvent} published on {@code fcourt.auth.events}
 * (key = userId). {@code token} is the secret the notification service puts in the emailed link;
 * only its hash is kept in {@code password_reset_tokens}.
 */
public record PasswordResetRequestedEvent(long userId, String email, String fullName, String token,
                                          Instant expiresAt) {
    public static final String TYPE = "PasswordResetRequestedEvent";
    public static final int VERSION = 1;
}
