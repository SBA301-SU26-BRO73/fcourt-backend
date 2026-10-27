package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.response.CourtSlotResponse;
import com.fcourt.discovery.service.CourtSlotService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/public/courts")
public class CourtSlotController {
    private final CourtSlotService service;

    public CourtSlotController(CourtSlotService service) {
        this.service = service;
    }

    @GetMapping("/{courtId}/slots")
    public ResponseEntity<List<CourtSlotResponse>> slots(@PathVariable long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.find(courtId, date));
    }
}
