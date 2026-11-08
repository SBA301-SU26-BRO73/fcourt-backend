package com.fcourt.discovery.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/**
 * {@code userId} and {@code bookingId} stay internal and are never serialized. {@code reviewerName} is a masked
 * display name and {@code reviewerAvatarUrl} a snapshot taken when the review was written (null when unknown).
 */
public record Review(long id, long branchId, long courtId, @JsonIgnore long bookingId, @JsonIgnore long userId,
        String reviewerName, String reviewerAvatarUrl,
        int rating, String comment, List<String> imageUrls, String adminReply, Instant createdAt, Instant repliedAt) {
    public static final String ANONYMOUS_NAME = "Khách hàng";
    public Review { imageUrls = List.copyOf(imageUrls); }
}
