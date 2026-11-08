package com.fcourt.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class AdminBranchAuthority {
    private final RestClient facility;

    AdminBranchAuthority(RestClient.Builder builder,
            @Value("${booking.facility-url:${FACILITY_URL:http://localhost:8083}}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        facility = builder.clone().requestFactory(factory).baseUrl(url).build();
    }

    List<Long> ownedBranches(Jwt jwt) {
        long actor = BookingQueries.actor(jwt);
        Object roles = jwt.getClaims().get("roles");
        boolean admin = roles instanceof Collection<?> values ? values.contains("ADMIN")
                : "ADMIN".equals(jwt.getClaimAsString("role"));
        if (!admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Court admin required");
        try {
            List<Branch> branches = facility.get().uri("/api/admin/branches")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(new ParameterizedTypeReference<List<Branch>>() {});
            if (branches == null) throw unavailable();
            var ids = new HashSet<Long>();
            for (Branch branch : branches) {
                if (branch == null || branch.id() == null || branch.id() <= 0
                        || branch.adminId() == null || branch.adminId() != actor || !ids.add(branch.id())) {
                    throw unavailable();
                }
            }
            return ids.stream().sorted().toList();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch ownership denied");
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Branch ownership verification unavailable");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(Long id, Long adminId) {}
}
