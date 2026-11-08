package com.fcourt.facility.integration;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads booking/hold occupancy from discovery-service, including occupancy hidden by the public
 * OUT_OF_SERVICE state (SCRUM-49). Used to refuse a lock that would collide with a booking or hold.
 * When the discovery URL/token is empty or discovery fails, locking fails closed with 503: a
 * conflict that cannot be checked is never assumed absent.
 */
@Component
public class SlotAvailabilityClient {
    public record SlotConflict(Long slotId, LocalTime startTime, LocalTime endTime, String status) { }

    private final RestClient client;
    private final String internalToken;

    public SlotAvailabilityClient(@Value("${facility.discovery.base-url:}") String baseUrl,
            @Value("${facility.discovery.internal-token:}") String internalToken) {
        this.internalToken = internalToken;
        if (baseUrl == null || baseUrl.isBlank()) {
            this.client = null;
        } else {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));
            factory.setReadTimeout(Duration.ofSeconds(2));
            this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
    }

    public List<SlotConflict> conflicts(long courtId, LocalDate date) {
        if (client == null || internalToken == null || internalToken.isBlank()) throw unavailable();
        try {
            SlotConflict[] conflicts = client.get()
                    .uri("/api/internal/courts/{id}/slot-conflicts?date={date}", courtId, date)
                    .header("X-Internal-Service-Token", internalToken)
                    .retrieve().body(SlotConflict[].class);
            if (conflicts == null) throw unavailable();
            for (SlotConflict conflict : conflicts) {
                if (conflict == null || conflict.slotId() == null || conflict.startTime() == null
                        || conflict.endTime() == null || conflict.status() == null
                        || !Set.of("BOOKED", "HOLD").contains(conflict.status())) {
                    throw unavailable();
                }
            }
            return List.of(conflicts);
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot verify existing bookings and holds right now; the lock was not created");
    }
}
