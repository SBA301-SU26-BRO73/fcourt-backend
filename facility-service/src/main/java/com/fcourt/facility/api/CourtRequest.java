package com.fcourt.facility.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CourtRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull @Positive Long courtTypeId,
        @Size(max = 2000) String description,
        @Size(max = 2048) String imageUrl,
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 10, fraction = 2) BigDecimal defaultPricePerHour,
        @Pattern(regexp = "ACTIVE|MAINTENANCE|INACTIVE", message = "must be ACTIVE, MAINTENANCE or INACTIVE")
        String status) {

    public record StatusChange(
            @NotNull @Pattern(regexp = "ACTIVE|MAINTENANCE|INACTIVE",
                    message = "must be ACTIVE, MAINTENANCE or INACTIVE") String status) { }
}
