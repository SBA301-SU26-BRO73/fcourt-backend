package com.fcourt.discovery.dto.request;

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
        @Min(1) @Max(100) Integer size,
        Boolean hasAvailableSlotsToday,
        @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9] *- *(?:[01][0-9]|2[0-3]):[0-5][0-9]",
                message = "timeSlot must use HH:mm - HH:mm") String timeSlot) {
    public BranchSearchRequest(String query, String city, String district, Long courtTypeId,
            BigDecimal minPrice, BigDecimal maxPrice, Double latitude, Double longitude,
            Integer page, Integer size) {
        this(query, city, district, courtTypeId, minPrice, maxPrice, latitude, longitude,
                page, size, null, null);
    }

    public boolean requiresAvailability() {
        return Boolean.TRUE.equals(hasAvailableSlotsToday) || timeSlot != null;
    }

    @AssertTrue(message = "timeSlot start must be before end; overnight ranges are unsupported")
    public boolean isTimeSlotOrdered() {
        if (timeSlot == null || !timeSlot.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9] *- *(?:[01][0-9]|2[0-3]):[0-5][0-9]")) return true;
        String[] parts = timeSlot.split(" *- *");
        return parts[0].compareTo(parts[1]) < 0;
    }
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
