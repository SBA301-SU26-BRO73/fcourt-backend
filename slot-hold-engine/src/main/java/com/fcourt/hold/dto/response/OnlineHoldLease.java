package com.fcourt.hold.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OnlineHoldLease(
    String holdHash,
    Instant expiresAt,
    long courtId,
    LocalDate date,
    List<Long> slotIds,
    String guestPhone) {}
