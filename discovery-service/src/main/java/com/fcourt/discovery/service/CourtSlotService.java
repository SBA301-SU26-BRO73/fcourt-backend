package com.fcourt.discovery.service;

import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.dto.response.BlockingSlotResponse;
import java.time.LocalDate;
import java.util.List;

public interface CourtSlotService {
    List<CourtSlotResponse> find(long courtId, LocalDate date);

    /** Returns booking/hold occupancy even when the public slot state is masked as OUT_OF_SERVICE. */
    List<BlockingSlotResponse> blockingSlots(long courtId, LocalDate date);
}
