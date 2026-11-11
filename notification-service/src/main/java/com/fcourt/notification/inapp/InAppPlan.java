package com.fcourt.notification.inapp;

/** One notification for one user. {@code dedupeKey} is unique per user: a replayed event adds nothing. */
public record InAppPlan(long userId, String type, String title, String body, String link, String dedupeKey) {
    private static final int MAX_BODY = 1000;

    public InAppPlan {
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY - 3) + "...";
        }
    }
}
