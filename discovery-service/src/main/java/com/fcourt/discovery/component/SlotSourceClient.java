package com.fcourt.discovery.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Component
public class SlotSourceClient {
    private final RestClient facility;
    private final RestClient booking;

    public SlotSourceClient(@Value("${discovery.slots.facility-url}") String facilityUrl,
                            @Value("${discovery.slots.booking-url}") String bookingUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        facility = RestClient.builder().baseUrl(facilityUrl).requestFactory(factory).build();
        booking = RestClient.builder().baseUrl(bookingUrl).requestFactory(factory).build();
    }

    public Schedule schedule(long courtId, LocalDate date) {
        try {
            return facility.get().uri("/api/internal/courts/{id}/slot-schedule?date={date}", courtId, date)
                    .retrieve().body(Schedule.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Court not found");
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public Occupancy occupancy(long courtId, LocalDate date) {
        try {
            return booking.get().uri("/api/internal/courts/{id}/slot-occupancy?date={date}", courtId, date)
                    .retrieve().body(Occupancy.class);
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Slot availability source unavailable or invalid");
    }

    public record Schedule(Long courtId, LocalDate date, Boolean outOfService, List<Slot> slots) { }
    public record Slot(Long slotId, LocalTime startTime, LocalTime endTime,
                       BigDecimal price, Boolean outOfService) { }
    public record Occupancy(Long courtId, LocalDate date, List<Long> bookedSlotIds) { }
}
