package com.fcourt.hold.client;

import com.fcourt.hold.dto.request.HoldRequest;
import com.fcourt.hold.dto.response.DiscoverySlot;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DiscoveryGateway {
    private final RestClient client;
    private final Clock clock;

    public DiscoveryGateway(RestClient discoveryClient, Clock clock) {
        this.client = discoveryClient;
        this.clock = clock;
    }

    public void requireAvailable(HoldRequest request) {
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"));
        if (request.date().isBefore(now.toLocalDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date is in the past");
        }
        DiscoverySlot[] slots;
        try {
            slots = client.get().uri("/api/public/courts/{courtId}/slots?date={date}",
                    request.courtId(), request.date()).retrieve().body(DiscoverySlot[].class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Court not found");
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (slots == null) throw unavailable();
        Map<Long, DiscoverySlot> byId;
        try {
            byId = Arrays.stream(slots).collect(Collectors.toMap(DiscoverySlot::slotId, Function.identity()));
        } catch (RuntimeException e) {
            throw unavailable();
        }
        for (Long id : request.slotIds()) {
            DiscoverySlot slot = byId.get(id);
            if (slot == null || slot.startTime() == null || slot.status() == null) throw unavailable();
            if (!"AVAILABLE".equals(slot.status()) || !request.date().atTime(slot.startTime()).isAfter(now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A requested slot is unavailable");
            }
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Discovery unavailable or invalid response");
    }
}
