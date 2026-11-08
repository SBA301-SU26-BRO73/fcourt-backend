package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client of facility-service's reservation authority (SCRUM-241): reserve, commit, rollback, all keyed by the
 * reservation id this service chooses. Every outcome that is not a definite answer is {@link Unavailable}: the caller
 * must treat it as "unknown" and compensate, never as success.
 */
@Component
class FullCourtFacility {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";

    /** Definite refusal by facility (4xx); {@code code} is its problem code, e.g. LOCK_OVERLAP. */
    static final class Refused extends RuntimeException {
        final int status;
        final String code;

        Refused(int status, String code) {
            super("Facility refused the reservation: " + status + " " + code);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    /** No definite answer (timeout, 5xx, bad body, missing credential). */
    static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record Reserve(UUID reservationId, long branchId, long adminId, List<Long> courtIds, String startAt, String endAt,
            Integer ttlSeconds, String publicReason, boolean relock) {
        Reserve(UUID reservationId, long branchId, long adminId, List<Long> courtIds, String startAt, String endAt,
                Integer ttlSeconds, String publicReason) {
            this(reservationId, branchId, adminId, courtIds, startAt, endAt, ttlSeconds, publicReason, false);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Reservation(UUID reservationId, String state, List<Long> courtIds) {}

    private final RestClient reserveClient;
    private final RestClient client;
    private final String token;

    FullCourtFacility(RestClient.Builder builder, @Value("${FACILITY_URL:http://localhost:8083}") String url,
            @Value("${FACILITY_RESERVATION_TOKEN:}") String token) {
        // Reserve fans out to discovery for every court/day, so it gets a longer read timeout than commit/rollback.
        this.reserveClient = build(builder, url, Duration.ofSeconds(20));
        this.client = build(builder, url, Duration.ofSeconds(5));
        this.token = token;
    }

    private static RestClient build(RestClient.Builder builder, String url, Duration read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(read);
        return builder.clone().requestFactory(factory).baseUrl(url).build();
    }

    Reservation reserve(Reserve body) {
        return call(() -> reserveClient.post().uri("/api/internal/court-reservations").header(TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Reservation.class), body.reservationId(), "RESERVED");
    }

    Reservation commit(UUID id) {
        return call(() -> client.post().uri("/api/internal/court-reservations/{id}/commit", id)
                .header(TOKEN_HEADER, token).retrieve().body(Reservation.class), id, "COMMITTED");
    }

    Reservation rollback(UUID id) {
        return call(() -> client.post().uri("/api/internal/court-reservations/{id}/rollback", id)
                .header(TOKEN_HEADER, token).retrieve().body(Reservation.class), id, "CANCELLED");
    }

    private Reservation call(java.util.function.Supplier<Reservation> action, UUID id, String expectedState) {
        if (token == null || token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new Unavailable("Facility reservation credential is not configured", null);
        }
        try {
            Reservation result = action.get();
            if (result == null || !id.equals(result.reservationId()) || !expectedState.equals(result.state())) {
                throw new Unavailable("Unexpected facility reservation answer", null);
            }
            return result;
        } catch (HttpClientErrorException error) {
            int status = error.getStatusCode().value();
            if (status == 408 || status == 429) throw new Unavailable("Facility busy", error);
            String code = null;
            try {
                Map<String, Object> body = error.getResponseBodyAs(new ParameterizedTypeReference<Map<String, Object>>() {});
                if (body != null && body.get("code") instanceof String text) code = text;
            } catch (RuntimeException unreadable) {
                // keep the bare status
            }
            throw new Refused(status, code);
        } catch (RestClientException error) {
            throw new Unavailable("Facility reservation authority unavailable", error);
        }
    }
}
