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

    PaymentUpstreams(@Qualifier("bookingClient") RestClient booking,
                     @Qualifier("facilityClient") RestClient facility) {
        this.booking = booking;
        this.facility = facility;
    }

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
                   BigDecimal totalPrice, String status, Instant holdExpiresAt) { }
    record Bank(Long id, String bankName, String bankBin, String bankAccountNumber, String bankAccountName) { }
}
