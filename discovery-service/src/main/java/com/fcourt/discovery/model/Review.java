package com.fcourt.discovery.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

public record Review(long id, long branchId, long courtId, @JsonIgnore long bookingId, long userId,
        int rating, String comment, List<String> imageUrls, String adminReply, Instant createdAt, Instant repliedAt) {
    public Review { imageUrls = List.copyOf(imageUrls); }
}
