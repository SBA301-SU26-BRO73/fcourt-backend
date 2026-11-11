package com.fcourt.notification.push;

import java.util.UUID;

public record PushPlan(UUID dedupEventId, long userId, long bookingId, String type, String title, String body) { }
