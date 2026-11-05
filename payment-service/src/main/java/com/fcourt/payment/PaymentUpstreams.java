package com.fcourt.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class PaymentUpstreams {
    private final RestClient booking;
    private final RestClient facility;
    private final String bookingIntegrationToken;

    PaymentUpstreams(@Qualifier("bookingClient") RestClient booking,
                     @Qualifier("facilityClient") RestClient facility) {
        this(booking, facility, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    PaymentUpstreams(@Qualifier("bookingClient") RestClient booking,
                     @Qualifier("facilityClient") RestClient facility,
                     @org.springframework.beans.factory.annotation.Value("${payment.booking-integration-token:${BOOKING_INTEGRATION_TOKEN:}}") String token) {
        this.booking = booking;
        this.facility = facility;
        this.bookingIntegrationToken = token;
    }

    void freeze(long id, String authorization, String holdToken, BigDecimal amount) {
        if (bookingIntegrationToken.isBlank()) throw unavailable();
        try {
            var ack = booking.post().uri("/api/internal/bookings/{id}/payment-freeze", id).headers(headers -> {
                headers.set("X-Service-Token", bookingIntegrationToken);
                if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
                if (holdToken != null) headers.set("X-Hold-Token", holdToken);
            }).body(java.util.Map.of("amount", amount)).retrieve().body(FreezeAck.class);
            if (ack == null || ack.id() == null || ack.id() != id || !ack.paymentFrozen()
                    || ack.totalPrice() == null || ack.totalPrice().compareTo(amount) != 0) throw unavailable();
        } catch (HttpClientErrorException error) {
            int status = error.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404 || status == 409 || status == 410)
                throw new ResponseStatusException(error.getStatusCode(), "Giá đơn đã thay đổi hoặc thanh toán không còn hợp lệ");
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record FreezeAck(Long id, BigDecimal totalPrice, boolean paymentFrozen) {}

    Booking booking(long id, String authorization, String holdToken) {
        try {
            var result = booking.get().uri("/api/bookings/{id}", id).headers(headers -> {
                if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
                if (holdToken != null) headers.set("X-Hold-Token", holdToken);
            }).retrieve().body(Booking.class);
            if (result == null) throw unavailable();
            return result;
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403 || code == 404 || code == 410 || code == 409) {
                throw new ResponseStatusException(e.getStatusCode(), switch (code) {
                    case 401 -> "Authentication required";
                    case 403 -> "Booking access denied";
                    case 404 -> "Booking not found";
                    case 410 -> "Hold expired";
                    default -> "Booking cannot be paid";
                });
            }
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    Bank bank(long branchId) {
        try {
            var result = facility.get().uri("/api/public/branches/{id}", branchId)
                    .retrieve().body(Bank.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout dependency unavailable or invalid response");
    }

    record Booking(Long id, String bookingCode, Long branchId, String branchName,
                   String courtName, LocalDate date, List<CheckoutInfo.Slot> slots,
                   BigDecimal totalPrice, String status, Instant holdExpiresAt, String customerName, String customerPhone, BigDecimal refundAmount) { }
    record Bank(Long id, String bankName, String bankBin, String bankAccountNumber, String bankAccountName) { }
}
