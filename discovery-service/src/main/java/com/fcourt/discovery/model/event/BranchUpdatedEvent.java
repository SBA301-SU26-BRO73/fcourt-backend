package com.fcourt.discovery.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** Full public snapshot, including every active, non-deleted court. */
public record BranchUpdatedEvent(
        @NotNull @Positive Long branchId,
        @NotNull @Positive Long version,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String district,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull Boolean active,
        @NotNull Boolean deleted,
        @NotNull @Size(max = 10000) List<@NotNull @Valid CourtSnapshot> courts,
        @Size(max = 2048) String imageUrl,
        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String openTime,
        @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String closeTime) {
    /** Snapshot without the card fields (older producers). */
    public BranchUpdatedEvent(Long branchId, Long version, String name, String address, String city, String district,
            Double latitude, Double longitude, Boolean active, Boolean deleted, List<CourtSnapshot> courts) {
        this(branchId, version, name, address, city, district, latitude, longitude, active, deleted, courts,
                null, null, null);
    }


    @JsonIgnore
    @AssertTrue(message = "latitude and longitude must be supplied together")
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    @AssertTrue(message = "court ids must be unique within a snapshot")
    public boolean isCourtIdsUnique() {
        return courts == null || courts.stream().filter(java.util.Objects::nonNull)
                .map(CourtSnapshot::id).distinct().count() == courts.size();
    }

    public record CourtSnapshot(
            @NotNull @Positive Long id,
            @NotNull @Positive Long courtTypeId,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minPrice,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal maxPrice) {
        @JsonIgnore
    @AssertTrue(message = "prices must be both absent or form an ordered range")
        public boolean isPriceRangeValid() {
            return minPrice == null ? maxPrice == null
                    : maxPrice != null && minPrice.compareTo(maxPrice) <= 0;
        }
    }
}
