package com.fcourt.staff.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AvailabilityGateway {
    private final RestClient discovery;

    public AvailabilityGateway(RestClient.Builder builder, @Value("${staff.discovery.url}") String url) {
        discovery = builder.clone().baseUrl(url).build();
    }

    public List<Slot> selected(long courtId, LocalDate date, List<Long> ids) {
        List<Slot> all;
        try {
            all = discovery.get().uri(uri -> uri.path("/api/public/courts/{id}/slots").queryParam("date", date).build(courtId))
                    .retrieve().body(new ParameterizedTypeReference<List<Slot>>() { });
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Court not found");
            throw BookingGateway.unavailable();
        } catch (RestClientException error) { throw BookingGateway.unavailable(); }
        if (all == null || all.size() > 48) throw BookingGateway.unavailable();
        var known = new HashSet<Long>();
        var starts = new HashSet<LocalTime>();
        for (var slot : all) {
            if (slot == null || slot.slotId() <= 0 || !known.add(slot.slotId()) || slot.startTime() == null
                    || slot.endTime() == null || slot.startTime().equals(slot.endTime()) || slot.price() == null
                    || slot.price().signum() < 0 || slot.price().scale() > 2 || slot.status() == null
                    || !Set.of("AVAILABLE", "HOLD", "BOOKED", "OUT_OF_SERVICE", "EXPIRED").contains(slot.status())
                    || !starts.add(slot.startTime()) || slot.startTime().getSecond() != 0 || slot.endTime().getSecond() != 0
                    || slot.startTime().getNano() != 0 || slot.endTime().getNano() != 0) throw BookingGateway.unavailable();
            long duration = Duration.between(slot.startTime(), slot.endTime()).toMinutes();
            if (duration < 0 && slot.endTime().equals(LocalTime.MIDNIGHT)) duration += 1440;
            if (duration != 30) throw BookingGateway.unavailable();
        }
        var selected = all.stream().filter(slot -> ids.contains(slot.slotId())).toList();
        if (selected.size() != ids.size() || selected.stream().anyMatch(slot -> !"AVAILABLE".equals(slot.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected slot is not available");
        }
        return selected;
    }

    public record Slot(long slotId, LocalTime startTime, LocalTime endTime, BigDecimal price, String status) { }
}
