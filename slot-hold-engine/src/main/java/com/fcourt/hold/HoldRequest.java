package com.fcourt.hold;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record HoldRequest(
        @NotNull @Positive Long courtId,
        @NotNull LocalDate date,
        @NotEmpty @Size(max = 48) List<@NotNull @Positive Long> slotIds,
        @NotNull @Pattern(regexp = "0[35789][0-9]{8}") String guestPhone) {
}
