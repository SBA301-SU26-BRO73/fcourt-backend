package com.fcourt.hold.client;

import com.fcourt.hold.dto.response.StaffAssignment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collection;

@Component
public class HoldStaffIdentity {
    private final JwtDecoder decoder;
    private final RestClient staff;

    public HoldStaffIdentity(
            RestClient.Builder builder,
            @Value("${AUTH_JWK_SET_URI:http://localhost:8081/.well-known/jwks.json}") String jwks,
            @Value("${AUTH_JWT_ISSUER:http://localhost:8081}") String issuer,
            @Value("${AUTH_JWT_AUDIENCE:fcourt}") String audience,
            @Value("${STAFF_URL:http://localhost:8090}") String url) {
        var jwt = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> aud =
                value ->
                        value.getAudience().contains(audience)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token"));
        jwt.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), aud));
        decoder = jwt;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        staff = builder.clone().requestFactory(factory).baseUrl(url).build();
    }

    public void require(String bearer, long branch, long actor) {
        try {
            if (bearer == null || !bearer.startsWith("Bearer ")) throw denied();
            var jwt = decoder.decode(bearer.substring(7));
            Object roles = jwt.getClaims().get("roles");
            boolean role =
                    roles instanceof Collection<?> c
                            ? c.contains("STAFF")
                            : "STAFF".equals(jwt.getClaimAsString("role"));
            if (!role
                    || branch <= 0
                    || actor <= 0
                    || !Long.toString(actor).equals(jwt.getSubject())) throw denied();
            var assigned =
                    staff.get()
                            .uri("/api/staff/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer)
                            .retrieve()
                            .body(StaffAssignment.class);
            if (assigned == null
                    || !assigned.active()
                    || assigned.userId() != actor
                    || assigned.branchId() != branch) throw denied();
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid staff token");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403)
                throw denied();
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    public static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Active branch staff required");
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Staff verification unavailable");
    }
}
