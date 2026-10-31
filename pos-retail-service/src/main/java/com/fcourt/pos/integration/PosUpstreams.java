package com.fcourt.pos.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fcourt.pos.api.PosViews;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PosUpstreams {
    private final RestClient auth;
    private final RestClient staff;
    private final RestClient facility;
    private final RestClient booking;
    private final String bookingToken;

    public PosUpstreams(RestClient.Builder builder, @Value("${pos.auth-url}") String authUrl,
                         @Value("${pos.staff-url}") String staffUrl, @Value("${pos.facility-url}") String facilityUrl,
                         @Value("${pos.booking-url}") String bookingUrl, @Value("${pos.booking-service-token}") String bookingToken) {
        auth = builder.clone().baseUrl(authUrl).build(); staff = builder.clone().baseUrl(staffUrl).build();
        facility = builder.clone().baseUrl(facilityUrl).build(); booking = builder.clone().baseUrl(bookingUrl).build();
        this.bookingToken = bookingToken;
        if (!bookingToken.isBlank() && bookingToken.length() < 32) throw new IllegalArgumentException("Booking service credential must be at least 32 characters");
    }

    public long staffBranch(Jwt jwt) {
        Assignment assignment = execute(() -> staff.get().uri("/api/staff/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt))
                .retrieve().body(Assignment.class));
        if (assignment == null || assignment.userId() != Long.parseLong(jwt.getSubject()) || assignment.branchId() <= 0) throw unavailable();
        if (!assignment.active()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff assignment is inactive");
        return assignment.branchId();
    }

    public void adminBranch(Jwt jwt, long branch) {
        User user = execute(() -> auth.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt)).retrieve().body(User.class));
        if (user == null || user.id() != Long.parseLong(jwt.getSubject())) throw unavailable();
        if (!"ACTIVE".equals(user.status()) || !"ADMIN".equals(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Inactive admin");
        Branch owner = execute(() -> facility.get().uri("/api/admin/branches/{id}", branch).header(HttpHeaders.AUTHORIZATION, bearer(jwt))
                .retrieve().body(Branch.class));
        if (owner == null || owner.id() != branch || owner.adminId() <= 0) throw unavailable();
        if (owner.adminId() != user.id() || !"ACTIVE".equals(owner.status())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch access denied");
    }

    public void bookingContext(Jwt jwt, long id, long branch) {
        if (bookingToken.isBlank()) throw unavailable();
        Context context = execute(() -> booking.get().uri("/api/internal/bookings/{id}/pos-context", id)
                .header(HttpHeaders.AUTHORIZATION, bearer(jwt)).retrieve().body(Context.class));
        if (context == null || context.id() != id || context.branchId() <= 0 || context.status() == null) throw unavailable();
        if (context.branchId() != branch) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Booking belongs to another branch");
        if (!List.of("CONFIRMED", "CHECKED_IN").contains(context.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking cannot accept POS charges");
    }

    public void attachCharge(PosViews.Order order) {
        if (bookingToken.isBlank()) throw unavailable();
        try {
            Charge result = booking.post().uri("/api/internal/bookings/{id}/pos-charges", order.bookingId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bookingToken)
                    .header("Idempotency-Key", order.orderCode()).body(new ChargeRequest(order.orderCode(), order.branchId(),
                            order.cashierUserId(), order.totalAmount(), order.items())).retrieve().body(Charge.class);
            if (result == null || !order.orderCode().equals(result.orderCode()) || result.bookingId() != order.bookingId()
                    || result.branchId() != order.branchId() || result.amount() == null || result.amount().compareTo(order.totalAmount()) != 0
                    || !"ACCEPTED".equals(result.status())) throw unavailable();
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() == 422) throw new ChargeRejected();
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }

    private <T> T execute(Call<T> call) {
        try { return call.run(); }
        catch (HttpClientErrorException error) {
            if (List.of(401, 403, 404, 409).contains(error.getStatusCode().value())) {
                throw new ResponseStatusException(error.getStatusCode(), "Authorization or booking context rejected");
            }
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }
    private String bearer(Jwt jwt) { return "Bearer " + jwt.getTokenValue(); }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "POS dependency unavailable or invalid response"); }
    @FunctionalInterface interface Call<T> { T run(); }
    public static class ChargeRejected extends RuntimeException { }
    @JsonIgnoreProperties(ignoreUnknown = true) record Assignment(long userId, long branchId, boolean active) { }
    @JsonIgnoreProperties(ignoreUnknown = true) record User(long id, String role, String status) { }
    @JsonIgnoreProperties(ignoreUnknown = true) record Branch(long id, long adminId, String status) { }
    @JsonIgnoreProperties(ignoreUnknown = true) record Context(long id, long branchId, String status) { }
    record ChargeRequest(String orderCode, long branchId, long cashierUserId, BigDecimal amount, List<PosViews.Item> items) { }
    record Charge(String orderCode, long bookingId, long branchId, BigDecimal amount, String status) { }
}
