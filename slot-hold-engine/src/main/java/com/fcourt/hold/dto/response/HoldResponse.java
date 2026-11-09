package com.fcourt.hold.dto.response;

import java.time.Instant;
import java.util.List;

public record HoldResponse(String holdToken, Instant expiresAt, int expiresInSeconds,
                           Long courtId, java.time.LocalDate date, List<Long> slotIds) {
}
