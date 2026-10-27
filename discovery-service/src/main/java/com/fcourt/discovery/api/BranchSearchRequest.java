package com.fcourt.discovery.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record BranchSearchRequest(
        @Size(max = 200) String query,
        @Size(max = 100) String city,
        @Size(max = 100) String district,
        @Positive Long courtTypeId,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minPrice,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal maxPrice,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @Min(0) @Max(1000000) Integer page,
        @Min(1) @Max(100) Integer size) {
    public int pageNumber() { return page == null ? 0 : page; }
    public int pageSize() { return size == null ? 20 : size; }

    @AssertTrue(message = "latitude and longitude must be supplied together")
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }

    @AssertTrue(message = "minPrice must not exceed maxPrice")
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }
}
