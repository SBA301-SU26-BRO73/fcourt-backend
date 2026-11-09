package com.fcourt.hold.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record CounterHoldRequest(
        @Positive long branchId,
        @Positive long actorId,
        @Positive long courtId,
        @NotNull LocalDate date,
        @NotEmpty @Size(max = 48) List<@NotNull @Positive Long> slotIds,
        @NotBlank @Size(max = 128) String commandKey) {}
