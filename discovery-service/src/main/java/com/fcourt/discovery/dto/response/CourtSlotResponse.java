package com.fcourt.discovery.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalTime;

public record CourtSlotResponse(long slotId, LocalTime startTime, LocalTime endTime,
                                BigDecimal price, Status status,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Restriction restriction) {
    public enum Status { AVAILABLE, HOLD, BOOKED, OUT_OF_SERVICE, EXPIRED }

    /** Public lock metadata (SCRUM-111): only present on OUT_OF_SERVICE slots locked by an owner. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Restriction(String type, String publicReason, String contactName, String contactPhone) { }

    public CourtSlotResponse(long slotId, LocalTime startTime, LocalTime endTime, BigDecimal price, Status status) {
        this(slotId, startTime, endTime, price, status, null);
    }
}
