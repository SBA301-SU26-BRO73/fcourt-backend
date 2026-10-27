package com.fcourt.discovery.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record BranchSearchResponse(List<BranchSummary> content, int page, int size,
                                   long totalElements, long totalPages) {
    public BranchSearchResponse {
        content = List.copyOf(content);
    }

    public record BranchSummary(long id, String name, String address, String city,
                                String district, Double latitude, Double longitude,
                                BigDecimal minPrice, BigDecimal maxPrice,
                                long courtCount, Double distanceKm) {}
}
