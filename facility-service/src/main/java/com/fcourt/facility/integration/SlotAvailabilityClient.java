package com.fcourt.facility.integration;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the live slot status (AVAILABLE / HOLD / BOOKED / OUT_OF_SERVICE / EXPIRED) from
 * discovery-service, which already merges facility schedule, booking occupancy and Redis holds
 * (SCRUM-22). Used to refuse a lock that would collide with a booking or an active hold.
 * When {@code DISCOVERY_BASE_URL} is empty or discovery fails, locking fails closed with 503:
 * a conflict that cannot be checked is never assumed absent.
 */
@Component
public class SlotAvailabilityClient {
    public record Slot(Long slotId, LocalTime startTime, LocalTime endTime, String status) { }

    private final RestClient client;

    public SlotAvailabilityClient(@Value("${facility.discovery.base-url:}") String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.client = null;
        } else {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofSeconds(2));
            this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
    }

    public List<Slot> slots(long courtId, LocalDate date) {
        if (client == null) throw unavailable();
        try {
            Slot[] slots = client.get().uri("/api/public/courts/{id}/slots?date={date}", courtId, date)
                    .retrieve().body(Slot[].class);
            if (slots == null) throw unavailable();
            for (Slot slot : slots) {
                if (slot == null || slot.slotId() == null || slot.startTime() == null || slot.endTime() == null
                        || slot.status() == null) throw unavailable();
            }
            return List.of(slots);
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot verify existing bookings and holds right now; the lock was not created");
    }
}
