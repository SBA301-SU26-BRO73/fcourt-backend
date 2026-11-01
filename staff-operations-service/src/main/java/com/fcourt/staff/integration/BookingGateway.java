package com.fcourt.staff.integration;

import java.time.Instant;
import java.math.BigDecimal;
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

    public WalkInResult walkIn(WalkInCommand request, String key, String token) {
        return execute(() -> booking.post().uri("/api/internal/bookings/walk-in")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
                .body(request).retrieve().body(WalkInResult.class));
    }

    public WalkInResult reconcileWalkIn(WalkInCommand request, String key, String token) {
        try {
            return execute(() -> booking.post().uri("/api/internal/bookings/walk-in/reconcile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
                    .body(request).retrieve().body(WalkInResult.class));
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 404) return null;
            throw error;
        }
    }

    public CheckoutResult checkout(long id, long branch, long actor, Instant completedAt, String key, String token) {
        return execute(() -> booking.put().uri("/api/internal/bookings/{id}/checkout", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
                .body(new CheckoutCommand(branch, actor, completedAt)).retrieve().body(CheckoutResult.class));
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
    public record WalkInCommand(long branchId, long actorId, long courtId, LocalDate date, List<Long> slotIds,
                                String guestPhone, String paymentMethod, Instant checkedInAt, CashQuote quote) { }
    public record WalkInResult(long bookingId, String bookingCode, long branchId, long courtId, LocalDate date,
                               String status, String paymentMethod, BigDecimal amount, Instant checkedInAt, CashInvoice invoice) { }
    public record ChargedSlot(long slotId, LocalTime startTime, LocalTime endTime, BigDecimal price) { }
    public record CashQuote(BigDecimal baseAmount, BigDecimal surchargeAmount, BigDecimal totalAmount,
                            String policyVersion, List<ChargedSlot> items) { }
    public record CashInvoice(String receiptCode, long cashierUserId, BigDecimal baseAmount,
                              BigDecimal surchargeAmount, BigDecimal totalAmount, String policyVersion, List<ChargedSlot> items) { }
    public record CheckoutCommand(long branchId, long actorId, Instant completedAt) { }
    public record CheckoutResult(long bookingId, long branchId, String status, Instant completedAt) { }
}
