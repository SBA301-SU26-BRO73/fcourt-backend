package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collection;

@Component
class StaffAuthority {
    private final RestClient staff;

    StaffAuthority(
            RestClient.Builder builder,
            @Value("${booking.staff-url:${STAFF_URL:http://localhost:8090}}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        staff = builder.clone().requestFactory(factory).baseUrl(url).build();
    }

    void require(Jwt jwt, long branch, long actor) {
        Object roles = jwt.getClaims().get("roles");
        boolean role =
                roles instanceof Collection<?> c
                        ? c.contains("STAFF")
                        : "STAFF".equals(jwt.getClaimAsString("role"));
        if (!role || branch <= 0 || actor <= 0 || !Long.toString(actor).equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff assignment required");
        }
        try {
            var assigned =
                    staff.get()
                            .uri("/api/staff/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                            .retrieve()
                            .body(Assignment.class);
            if (assigned == null
                    || assigned.userId() != actor
                    || assigned.branchId() != branch
                    || !assigned.active()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Staff assignment required");
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Staff assignment required");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Staff verification unavailable");
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Staff verification unavailable");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Assignment(long userId, long branchId, boolean active) {}
}
