package com.fcourt.discovery.service;

import com.fcourt.discovery.dto.response.CourtSlotResponse;
import java.time.LocalDate;
import java.util.List;

public interface CourtSlotService {
    List<CourtSlotResponse> find(long courtId, LocalDate date);
}
