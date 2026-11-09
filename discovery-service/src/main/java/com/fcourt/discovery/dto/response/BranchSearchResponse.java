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
                                long courtCount, Double distanceKm, BigDecimal averageRating, long reviewCount,
                                String imageUrl, String openTime, String closeTime, List<Long> courtTypeIds) {
        public BranchSummary {
            courtTypeIds = courtTypeIds == null ? List.of() : List.copyOf(courtTypeIds);
        }

        /** Card fields (cover photo, opening hours, court type ids) default to empty. */
        public BranchSummary(long id, String name, String address, String city, String district,
                Double latitude, Double longitude, BigDecimal minPrice, BigDecimal maxPrice, long courtCount,
                Double distanceKm, BigDecimal averageRating, long reviewCount) {
            this(id, name, address, city, district, latitude, longitude, minPrice, maxPrice, courtCount, distanceKm,
                    averageRating, reviewCount, null, null, null, List.of());
        }

        public BranchSummary(long id, String name, String address, String city, String district,
                Double latitude, Double longitude, BigDecimal minPrice, BigDecimal maxPrice, long courtCount, Double distanceKm) {
            this(id, name, address, city, district, latitude, longitude, minPrice, maxPrice, courtCount, distanceKm, new BigDecimal("0.00"), 0);
        }
    }
}
