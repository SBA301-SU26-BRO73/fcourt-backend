package com.fcourt.discovery.component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the reviewer's own profile from auth-service with the caller's bearer token (discovery never reads the auth DB).
 * Best effort: a failure must not block the review, so it yields an anonymous snapshot.
 */
@Component
public class ReviewerProfileClient {
    private static final Logger log = LoggerFactory.getLogger(ReviewerProfileClient.class);

    /** Display name already masked for public use; both fields may be null. */
    public record Snapshot(String displayName, String avatarUrl) {
        public static final Snapshot ANONYMOUS = new Snapshot(null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(String fullName, String avatarUrl) {}

    private final RestClient auth;

    public ReviewerProfileClient(RestClient.Builder builder, @Value("${discovery.auth.base-url}") String url) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        auth = builder.clone().baseUrl(url).requestFactory(factory).build();
    }

    public Snapshot snapshot(Jwt jwt) {
        try {
            Profile profile = auth.get().uri("/api/auth/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                    .retrieve().body(Profile.class);
            if (profile == null) return Snapshot.ANONYMOUS;
            return new Snapshot(ReviewerNames.mask(profile.fullName()), safeAvatar(profile.avatarUrl()));
        } catch (RestClientException error) {
            log.warn("Reviewer profile unavailable, saving review without name/avatar: {}", error.getMessage());
            return Snapshot.ANONYMOUS;
        }
    }

    static String safeAvatar(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null ? value.strip() : null;
        } catch (IllegalArgumentException error) { return null; }
    }
}
