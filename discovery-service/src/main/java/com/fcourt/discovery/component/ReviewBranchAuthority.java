package com.fcourt.discovery.component;

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
public class ReviewBranchAuthority {
    private final RestClient facility;
    public ReviewBranchAuthority(RestClient.Builder builder, @Value("${discovery.slots.facility-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        facility = builder.clone().baseUrl(url).requestFactory(factory).build();
    }

    public List<Long> owned(Jwt jwt) {
        long actor = ReviewIdentity.require(jwt, "ADMIN");
        try {
            List<Branch> rows = facility.get().uri("/api/admin/branches").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(new ParameterizedTypeReference<List<Branch>>() {});
            if (rows == null) throw unavailable();
            var ids = new HashSet<Long>();
            for (Branch branch : rows) {
                if (branch == null || branch.id() == null || branch.id() <= 0 || branch.adminId() == null
                        || branch.adminId() != actor || !ids.add(branch.id())) throw unavailable();
            }
            return List.copyOf(ids);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý chi nhánh");
            throw unavailable();
        } catch (RestClientException error) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể xác minh quyền chi nhánh");
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(Long id, Long adminId) {}
}
