package com.fcourt.hold.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OnlineHoldClaimRequest(
    @NotNull UUID holdToken,
    @Positive long actorId,
    @NotNull UUID commandKey,
    @Positive Long courtId,
    @NotNull LocalDate date,
    @NotEmpty @Size(max = 48) List<@NotNull @Positive Long> slotIds) {
  @Override
  public String toString() {
    return "OnlineHoldClaim[REDACTED]";
  }
}
