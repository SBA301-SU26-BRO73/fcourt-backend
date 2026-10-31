package com.fcourt.facility.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;

/** Create/update body for a branch (full replacement on update). */
public record BranchRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String address,
        @Size(max = 100) String ward,
        @Size(max = 100) String district,
        @NotBlank @Size(max = 100) String city,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "must contain 8-15 digits") String phone,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        Integer slotDurationMinutes,
        @NotBlank @Size(max = 100) String bankName,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String bankBin,
        @NotBlank @Pattern(regexp = "^[0-9]{6,20}$", message = "must be 6-20 digits") String bankAccountNumber,
        @NotBlank @Size(max = 150) String bankAccountName,
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE") String status) {

    @JsonIgnore
    @AssertTrue(message = "closeTime must be after openTime")
    public boolean isCloseAfterOpen() {
        return openTime == null || closeTime == null || closeTime.isAfter(openTime);
    }

    @JsonIgnore
    @AssertTrue(message = "latitude and longitude must be supplied together")
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    @AssertTrue(message = "slotDurationMinutes must be 30 or 60")
    public boolean isSlotDurationValid() {
        return slotDurationMinutes == null || slotDurationMinutes == 30 || slotDurationMinutes == 60;
    }

    public int slotDurationOrDefault() {
        return slotDurationMinutes == null ? 30 : slotDurationMinutes;
    }
}
