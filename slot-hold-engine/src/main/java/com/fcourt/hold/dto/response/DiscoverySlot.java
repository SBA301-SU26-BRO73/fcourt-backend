package com.fcourt.hold.dto.response;

import java.time.LocalTime;

public record DiscoverySlot(Long slotId, String status, LocalTime startTime) { }
