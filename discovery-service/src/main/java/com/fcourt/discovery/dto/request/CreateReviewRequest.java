package com.fcourt.discovery.dto.request;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;

public record CreateReviewRequest(@NotNull @Positive Long bookingId,
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max=2000) String comment,
        @Size(max=5) List<@NotBlank @Size(max=2048) String> imageUrls) {
    public CreateReviewRequest {
        comment = comment == null || comment.isBlank() ? null : comment.trim();
        imageUrls = imageUrls == null ? List.of() : imageUrls.stream().map(value -> value == null ? null : value.trim()).toList();
    }

    @AssertTrue(message="imageUrls must contain valid HTTPS URLs without embedded credentials")
    public boolean isImagesValid() {
        for (String image : imageUrls) {
            if (image == null) return false;
            try {
                URI uri = URI.create(image);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) return false;
            } catch (IllegalArgumentException error) { return false; }
        }
        return true;
    }
}
