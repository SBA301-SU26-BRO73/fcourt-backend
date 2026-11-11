package com.fcourt.voucher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class VoucherBookingClient {
    private final RestClient booking;
    private final String token;
    VoucherBookingClient(RestClient.Builder builder, @Value("${voucher.booking-url:${BOOKING_URL:http://localhost:8088}}") String url,
            @Value("${voucher.booking-integration-token:${BOOKING_INTEGRATION_TOKEN:}}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        booking = builder.clone().baseUrl(url).requestFactory(factory).build(); this.token = token;
    }

    Snapshot owned(long id, Jwt jwt) {
        try {
            return checked(booking.get().uri("/api/bookings/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(Snapshot.class), id);
        } catch (HttpClientErrorException error) { throw denied(error); }
        catch (RestClientException error) { throw unavailable(); }
    }
    Snapshot internal(long id) {
        requireToken();
        try {
            return checked(booking.get().uri("/api/internal/bookings/{id}/voucher-snapshot", id)
                    .header("X-Service-Token", token).retrieve().body(Snapshot.class), id);
        } catch (RestClientException error) { throw unavailable(); }
    }
    void attach(VoucherRedemption application, Jwt jwt) {
        requireToken();
        try {
            var result = checked(booking.post().uri("/api/internal/bookings/{id}/voucher", application.bookingId())
                    .header("X-Service-Token", token).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .body(new Attachment(application.applicationId(), application.voucherId(), application.originalAmount(),
                            application.discountAmount(), application.finalAmount())).retrieve().body(Snapshot.class), application.bookingId());
            if (!application.applicationId().equals(result.voucherApplicationId()) || result.customerId() == null
                    || result.customerId() != application.customerId() || result.branchId() != application.branchId()
                    || result.voucherId() == null || result.voucherId() != application.voucherId()
                    || result.originalAmount() == null || result.discountAmount() == null
                    || result.originalAmount().compareTo(application.originalAmount()) != 0
                    || result.discountAmount().compareTo(application.discountAmount()) != 0
                    || result.totalPrice().compareTo(application.finalAmount()) != 0) throw unavailable();
        } catch (HttpClientErrorException error) { throw denied(error); }
        catch (RestClientException error) { throw unavailable(); }
    }
    /** Undo attach: booking must clear the voucher columns and restore total_price to original_amount (idempotent). */
    Snapshot detach(VoucherRedemption application, Jwt jwt) {
        requireToken();
        try {
            var result = checked(booking.delete().uri("/api/internal/bookings/{id}/voucher", application.bookingId())
                    .header("X-Service-Token", token).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(Snapshot.class), application.bookingId());
            if (result.voucherApplicationId() != null || result.totalPrice().compareTo(application.originalAmount()) != 0) throw unavailable();
            return result;
        } catch (HttpClientErrorException error) {
            int status = error.getStatusCode().value();
            if (status == 404 || status == 405) throw unavailable();
            throw denied(error);
        }
        catch (RestClientException error) { throw unavailable(); }
    }
    private void requireToken() { if (token.isBlank()) throw unavailable(); }
    private Snapshot checked(Snapshot value, long id) {
        if (value == null || value.id() == null || value.id() != id || value.branchId() == null || value.branchId() <= 0
                || value.totalPrice() == null || value.totalPrice().signum() < 0 || value.status() == null) throw unavailable();
        return value;
    }
    private ResponseStatusException denied(HttpClientErrorException error) {
        int status = error.getStatusCode().value();
        if (status == 400 || status == 401 || status == 403 || status == 404 || status == 409 || status == 410)
            return new ResponseStatusException(error.getStatusCode(), "Đơn đặt sân không hợp lệ, không thuộc bạn hoặc đã khóa thanh toán");
        return unavailable();
    }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh đơn đặt sân, vui lòng thử lại"); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snapshot(Long id, Long customerId, Long branchId, String status, Instant holdExpiresAt,
            UUID voucherApplicationId, Long voucherId, BigDecimal originalAmount, BigDecimal discountAmount,
            BigDecimal totalPrice, boolean paymentFrozen) {
        BigDecimal gross() { return originalAmount == null ? totalPrice : originalAmount; }
    }
    record Attachment(UUID applicationId, long voucherId, BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal finalAmount) {}
}
