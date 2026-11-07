package com.fcourt.voucher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
class VoucherAuthority {
    private final RestClient facility;

    VoucherAuthority(RestClient.Builder builder, @Value("${voucher.facility-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        facility = builder.clone().baseUrl(url).requestFactory(factory).build();
    }

    List<Long> branches(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long actor;
        try {
            actor = Long.parseLong(jwt.getSubject());
            if (actor <= 0) throw new NumberFormatException();
        } catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        Object claim = jwt.getClaims().get("roles");
        Collection<?> roles = claim instanceof Collection<?> values ? values : List.of(
                Objects.toString(jwt.getClaims().get("role"), ""));
        boolean superAdmin = roles.contains("SUPER_ADMIN");
        if (!superAdmin && !roles.contains("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            List<Branch> rows = facility.get().uri("/api/admin/branches")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(new ParameterizedTypeReference<List<Branch>>() {});
            if (rows == null) throw unavailable();
            var ids = new TreeSet<Long>();
            for (Branch row : rows) {
                if (row == null || row.id() == null || row.id() <= 0 || row.adminId() == null || row.adminId() <= 0
                        || (!superAdmin && row.adminId() != actor) || !ids.add(row.id())) throw unavailable();
            }
            return List.copyOf(ids);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Branch verification unavailable");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(Long id, Long adminId) {}
}
