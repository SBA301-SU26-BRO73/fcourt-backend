package com.fcourt.staff.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BookingGateway {
    private final RestClient booking;

    public BookingGateway(RestClient.Builder builder, @Value("${staff.booking.url}") String url) {
        booking = builder.clone().baseUrl(url).build();
    }

    public Schedule schedule(long branch, LocalDate start, LocalDate end, String token) {
        return execute(() -> booking.get().uri(uri -> uri.path("/api/internal/branches/{id}/schedule")
                .queryParam("startDate", start).queryParam("endDate", end).build(branch))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Schedule.class));
    }

    public CheckInResult checkIn(CheckInCommand request, String key, String token) {
        return execute(() -> booking.post().uri("/api/internal/bookings/check-in")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
                .body(request).retrieve().body(CheckInResult.class));
    }

    private <T> T execute(Call<T> operation) {
        try {
            T value = operation.run();
            if (value == null) throw unavailable();
            return value;
        } catch (HttpClientErrorException error) {
            if (List.of(400, 401, 403, 404, 409).contains(error.getStatusCode().value())) {
                throw new ResponseStatusException(error.getStatusCode(), "Booking command rejected");
            }
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Booking service unavailable or invalid response");
    }

    @FunctionalInterface private interface Call<T> { T run(); }
    public record Schedule(long branchId, List<Court> courts) { }
    public record Court(long courtId, String courtName, List<Session> slots) { }
    public record Session(long bookingId, LocalDate date, LocalTime slotStart, LocalTime slotEnd,
                          String status, String customerName, String guestPhone) { }
    public record CheckInCommand(long branchId, long actorId, String checkInCode, LocalDate date,
                                 Instant checkedInAt, String channel) { }
    public record CheckInResult(long bookingId, long branchId, LocalDate date, String status, Instant checkedInAt) { }
}
