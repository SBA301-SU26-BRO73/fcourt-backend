package com.fcourt.notification.inapp;

import java.time.Instant;

/** What the web bell shows. {@code readAt} is null while unread. */
public record InAppNotification(long id, String type, String title, String body, String link, Instant readAt,
                                Instant createdAt) {
}
