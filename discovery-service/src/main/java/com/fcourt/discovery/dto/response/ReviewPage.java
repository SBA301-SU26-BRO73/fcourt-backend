package com.fcourt.discovery.dto.response;

import com.fcourt.discovery.model.Review;
import java.math.BigDecimal;
import java.util.List;

public record ReviewPage(List<Review> content, int page, int size, long totalElements, long totalPages,
        BigDecimal averageRating, List<StarCount> starDistribution) {
    public ReviewPage { content = List.copyOf(content); starDistribution = List.copyOf(starDistribution); }
    public record StarCount(int rating, long count, BigDecimal percentage) {}
}
