package com.fcourt.discovery.dto.response;

import java.math.BigDecimal;
import java.time.LocalTime;

public record CourtSlotResponse(long slotId, LocalTime startTime, LocalTime endTime,
                                BigDecimal price, Status status) {
    public enum Status { AVAILABLE, HOLD, BOOKED, OUT_OF_SERVICE, EXPIRED }
}
