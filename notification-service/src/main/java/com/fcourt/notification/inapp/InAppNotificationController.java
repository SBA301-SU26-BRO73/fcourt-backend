package com.fcourt.notification.inapp;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user's own in-app notifications; the user id always comes from the JWT subject. */
@RestController
@RequestMapping("/api/notifications")
class InAppNotificationController {
    private static final int MAX_SIZE = 50;
    private final InAppNotificationRepository notifications;

    InAppNotificationController(InAppNotificationRepository notifications) {
        this.notifications = notifications;
    }

    record Page(List<InAppNotification> items, int page, int size, long total) {
    }

    record UnreadCount(long count) {
    }

    record ReadAll(int updated) {
    }

    @GetMapping("/me")
    @Operation(summary = "My notifications, newest first")
    Page mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
              @RequestParam(defaultValue = "10") int size) {
        long user = userId(jwt);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        return new Page(notifications.newest(user, safeSize, (long) safePage * safeSize), safePage, safeSize,
                notifications.count(user));
    }

    @GetMapping("/me/unread-count")
    @Operation(summary = "How many of my notifications are unread")
    UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCount(notifications.unread(userId(jwt)));
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one of my notifications read")
    void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        // Someone else's id answers 404 exactly like a missing one.
        if (!notifications.markRead(userId(jwt), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    @PatchMapping("/me/read-all")
    @Operation(summary = "Mark all my notifications read")
    ReadAll markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return new ReadAll(notifications.markAllRead(userId(jwt)));
    }

    private static long userId(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // Invalid subjects are unauthorized.
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user subject");
    }
}
