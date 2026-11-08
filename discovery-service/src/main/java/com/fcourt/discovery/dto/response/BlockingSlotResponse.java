package com.fcourt.discovery.dto.response;

import java.time.LocalTime;

/** Internal occupancy view used by facility-service to reject locks over bookings or active holds. */
public record BlockingSlotResponse(long slotId, LocalTime startTime, LocalTime endTime, String status) { }
