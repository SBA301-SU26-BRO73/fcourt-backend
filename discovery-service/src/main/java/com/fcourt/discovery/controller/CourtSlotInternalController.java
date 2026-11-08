package com.fcourt.discovery.controller;

import com.fcourt.discovery.dto.response.BlockingSlotResponse;
import com.fcourt.discovery.service.CourtSlotService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Internal occupancy endpoint for facility lock validation; it contains no customer identity. */
@RestController
@RequestMapping("/api/internal/courts")
public class CourtSlotInternalController {
    private final CourtSlotService service;
    private final String internalToken;

    public CourtSlotInternalController(CourtSlotService service,
            @Value("${discovery.internal.slot-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @GetMapping("/{courtId}/slot-conflicts")
    public List<BlockingSlotResponse> blockingSlots(@PathVariable long courtId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String suppliedToken) {
        if (internalToken == null || internalToken.isBlank() || suppliedToken == null
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
        return service.blockingSlots(courtId, date);
    }
}
