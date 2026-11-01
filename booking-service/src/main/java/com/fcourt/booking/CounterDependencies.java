package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
class CounterDependencies {
    private final RestClient facility, discovery, holds;
    private final String credential;

    CounterDependencies(
            RestClient.Builder builder,
            @Value("${FACILITY_URL:http://localhost:8083}") String facility,
            @Value("${DISCOVERY_URL:http://localhost:8085}") String discovery,
            @Value("${HOLD_URL:http://localhost:8087}") String holds,
            @Value("${BOOKING_HOLD_SERVICE_TOKEN:}") String credential) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var configured = builder.clone().requestFactory(factory);
        this.facility = configured.clone().baseUrl(facility).build();
        this.discovery = configured.clone().baseUrl(discovery).build();
        this.holds = configured.clone().baseUrl(holds).build();
        this.credential = credential;
    }

    List<Court> courts(long branch, Jwt jwt) {
        return call(
                () -> {
                    var result =
                            facility.get()
                                    .uri("/api/staff/branches/{id}/courts", branch)
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            "Bearer " + jwt.getTokenValue())
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<List<Court>>() {});
                    if (result == null
                            || result.size() > 100
                            || result.stream()
                                    .anyMatch(
                                            c ->
                                                    c.id() <= 0
                                                            || c.branchId() != branch
                                                            || c.name() == null)
                            || result.stream().map(Court::id).distinct().count() != result.size())
                        throw unavailable();
                    return result;
                });
    }

    List<Slot> slots(long court, LocalDate date) {
        return call(
                () -> {
                    var result =
                            discovery
                                    .get()
                                    .uri(
                                            uri ->
                                                    uri.path("/api/public/courts/{id}/slots")
                                                            .queryParam("date", date)
                                                            .build(court))
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<List<Slot>>() {});
                    if (result == null || result.size() > 48) throw unavailable();
                    var ids = new HashSet<Long>();
                    var starts = new HashSet<LocalTime>();
                    for (var slot : result) {
                        if (slot == null
                                || slot.slotId() <= 0
                                || !ids.add(slot.slotId())
                                || slot.startTime() == null
                                || slot.endTime() == null
                                || !starts.add(slot.startTime())
                                || slot.price() == null
                                || slot.price().signum() < 0
                                || slot.price().scale() > 2
                                || slot.status() == null) throw unavailable();
                        long minutes =
                                Duration.between(slot.startTime(), slot.endTime()).toMinutes();
                        if (minutes < 0 && slot.endTime().equals(LocalTime.MIDNIGHT))
                            minutes += 1440;
                        if (minutes != 30
                                || slot.startTime().getSecond() != 0
                                || slot.startTime().getNano() != 0
                                || slot.endTime().getSecond() != 0
                                || slot.endTime().getNano() != 0) throw unavailable();
                    }
                    return result;
                });
    }

    List<Slot> canonicalSlots(long court, LocalDate date) {
        return call(
                () -> {
                    var result =
                            facility.get()
                                    .uri(
                                            uri ->
                                                    uri.path(
                                                                    "/api/internal/courts/{id}/slot-schedule")
                                                            .queryParam("date", date)
                                                            .build(court))
                                    .retrieve()
                                    .body(CanonicalSchedule.class);
                    if (result == null
                            || result.courtId() != court
                            || !date.equals(result.date())
                            || result.slots() == null
                            || result.slots().size() > 48) throw unavailable();
                    var ids = new HashSet<Long>();
                    var starts = new HashSet<LocalTime>();
                    var slots = new java.util.ArrayList<Slot>();
                    for (var item : result.slots()) {
                        if (item.slotId() <= 0
                                || !ids.add(item.slotId())
                                || item.startTime() == null
                                || !starts.add(item.startTime())
                                || item.endTime() == null
                                || item.price() == null
                                || item.price().signum() < 0
                                || item.price().scale() > 2) throw unavailable();
                        long duration =
                                Duration.between(item.startTime(), item.endTime()).toMinutes();
                        if (duration < 0 && item.endTime().equals(LocalTime.MIDNIGHT))
                            duration += 1440;
                        if (duration != 30) throw unavailable();
                        slots.add(
                                new Slot(
                                        item.slotId(),
                                        item.startTime(),
                                        item.endTime(),
                                        item.price(),
                                        item.outOfService() || result.outOfService()
                                                ? "OUT_OF_SERVICE"
                                                : "AVAILABLE"));
                    }
                    return slots;
                });
    }

    void reserve(
            long branch,
            long actor,
            long court,
            LocalDate date,
            List<Long> slots,
            String key,
            Jwt jwt) {
        if (credential.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw unavailable();
        call(
                () -> {
                    var lease =
                            holds.post()
                                    .uri("/api/internal/counter-holds")
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            "Bearer " + jwt.getTokenValue())
                                    .header("X-Booking-Service-Token", credential)
                                    .body(new Hold(branch, actor, court, date, slots, key))
                                    .retrieve()
                                    .body(Lease.class);
                    if (lease == null
                            || lease.token() == null
                            || lease.expiresAt() == null
                            || !lease.expiresAt().isAfter(Instant.now().plusSeconds(30))
                            || lease.courtId() != court
                            || !date.equals(lease.date())
                            || lease.slotIds() == null
                            || !new HashSet<>(slots).equals(new HashSet<>(lease.slotIds()))
                            || lease.slotIds().size() != slots.size()) throw unavailable();
                    return lease;
                });
    }

    private <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (HttpClientErrorException e) {
            if (Set.of(400, 401, 403, 404, 409).contains(e.getStatusCode().value()))
                throw new ResponseStatusException(e.getStatusCode(), "Counter dependency rejected");
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Counter authority dependency unavailable");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Court(long id, long branchId, String name, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Slot(
            long slotId, LocalTime startTime, LocalTime endTime, BigDecimal price, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CanonicalSchedule(
            long courtId, LocalDate date, boolean outOfService, List<CanonicalSlot> slots) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CanonicalSlot(
            long slotId,
            LocalTime startTime,
            LocalTime endTime,
            BigDecimal price,
            boolean outOfService) {}

    record Hold(
            long branchId,
            long actorId,
            long courtId,
            LocalDate date,
            List<Long> slotIds,
            String commandKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Lease(
            String token, Instant expiresAt, long courtId, LocalDate date, List<Long> slotIds) {}
}
