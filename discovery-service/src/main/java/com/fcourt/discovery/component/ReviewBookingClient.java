package com.fcourt.discovery.component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReviewBookingClient {
    private final RestClient booking;
    public ReviewBookingClient(RestClient.Builder builder, @Value("${discovery.slots.booking-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        booking = builder.clone().baseUrl(url).requestFactory(factory).build();
    }

    public CompletedBooking completed(long id, Jwt jwt) {
        try {
            var result = booking.get().uri("/api/bookings/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(CompletedBooking.class);
            if (result == null || result.id() == null || result.id() != id || result.branchId() == null || result.branchId() <= 0
                    || result.courtId() == null || result.courtId() <= 0 || result.status() == null
                    || !Set.of("PENDING_PAYMENT","AWAITING_CONFIRMATION","CONFIRMED","CHECKED_IN","COMPLETED","CANCELLED").contains(result.status()))
                throw unavailable();
            if (!"COMPLETED".equals(result.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được đánh giá sau khi đã hoàn thành chơi");
            return result;
        } catch (HttpClientErrorException error) {
            int status = error.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404)
                throw new ResponseStatusException(error.getStatusCode(), "Không tìm thấy đơn đặt sân của bạn");
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh đơn đặt sân, vui lòng thử lại");
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompletedBooking(Long id, Long branchId, Long courtId, String status) {}
}
