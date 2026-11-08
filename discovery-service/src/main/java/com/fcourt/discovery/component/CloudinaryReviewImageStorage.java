package com.fcourt.discovery.component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cloudinary signed upload (same approach as facility-service branch gallery, SCRUM-47).
 * Not exercised against a real Cloudinary account: unit tests use a fake HTTP server.
 */
public class CloudinaryReviewImageStorage implements ReviewImageStorage {
    private static final Logger log = LoggerFactory.getLogger(CloudinaryReviewImageStorage.class);

    /** Credentials from CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET or CLOUDINARY_URL. */
    public record Credentials(String cloudName, String apiKey, String apiSecret) {
        public boolean complete() { return notBlank(cloudName) && notBlank(apiKey) && notBlank(apiSecret); }

        /** Parses {@code cloudinary://API_KEY:API_SECRET@CLOUD_NAME}; returns empty credentials when malformed. */
        public static Credentials fromUrl(String url) {
            if (!notBlank(url) || !url.startsWith("cloudinary://")) return new Credentials(null, null, null);
            String rest = url.substring("cloudinary://".length());
            int at = rest.lastIndexOf('@');
            int colon = rest.indexOf(':');
            if (at < 0 || colon < 0 || colon > at) return new Credentials(null, null, null);
            return new Credentials(rest.substring(at + 1), rest.substring(0, colon), rest.substring(colon + 1, at));
        }

        private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UploadResult(String secure_url, String public_id) { }

    private final Credentials credentials;
    private final RestClient client;
    private final Clock clock;

    public CloudinaryReviewImageStorage(Credentials credentials, String baseUrl, Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder().baseUrl(baseUrl + "/v1_1/" + credentials.cloudName()).requestFactory(factory).build();
    }

    /** SHA-1 hex of the alphabetically sorted {@code k=v&...} parameters followed by the API secret. */
    static String sign(Map<String, String> params, String apiSecret) {
        String toSign = new TreeMap<>(params).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest((toSign + apiSecret).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    @Override
    public String store(String folder, Upload upload) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // The client file name is never forwarded: Cloudinary generates the public id.
        form.add("file", new ByteArrayResource(upload.content()) {
            @Override public String getFilename() { return "image"; }
        });
        form.add("api_key", credentials.apiKey());
        form.add("folder", folder);
        form.add("timestamp", timestamp);
        form.add("signature", sign(Map.of("folder", folder, "timestamp", timestamp), credentials.apiSecret()));
        try {
            UploadResult result = client.post().uri("/image/upload").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().body(UploadResult.class);
            if (result == null || result.secure_url() == null) throw unavailable("Cloudinary upload returned no url", null);
            return result.secure_url();
        } catch (RestClientException error) {
            throw unavailable("Cloudinary upload failed: " + error.getMessage(), error);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resource(String public_id, String secure_url, String created_at) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Listing(java.util.List<Resource> resources, String next_cursor) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DestroyResult(String result) { }

    @Override
    public boolean supportsCleanup() { return true; }

    /** Admin API listing (basic auth with the API key/secret). */
    @Override
    public AssetPage list(String prefix, String cursor) {
        try {
            Listing listing = client.get().uri(uri -> {
                var builder = uri.path("/resources/image/upload").queryParam("prefix", prefix).queryParam("max_results", 100);
                if (cursor != null) builder.queryParam("next_cursor", cursor);
                return builder.build();
            }).headers(headers -> headers.setBasicAuth(credentials.apiKey(), credentials.apiSecret()))
                    .retrieve().body(Listing.class);
            if (listing == null || listing.resources() == null) throw unavailable("Cloudinary listing returned nothing", null);
            var assets = new java.util.ArrayList<StoredAsset>();
            for (Resource resource : listing.resources()) {
                if (resource.public_id() == null || resource.secure_url() == null || resource.created_at() == null) continue;
                assets.add(new StoredAsset(resource.public_id(), resource.secure_url(), java.time.Instant.parse(resource.created_at())));
            }
            return new AssetPage(java.util.List.copyOf(assets), listing.next_cursor() == null || listing.next_cursor().isBlank() ? null : listing.next_cursor());
        } catch (RestClientException | java.time.format.DateTimeParseException error) {
            throw unavailable("Cloudinary listing failed: " + error.getClass().getSimpleName(), error);
        }
    }

    @Override
    public void delete(String publicId) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("public_id", publicId);
        form.add("api_key", credentials.apiKey());
        form.add("timestamp", timestamp);
        form.add("signature", sign(Map.of("public_id", publicId, "timestamp", timestamp), credentials.apiSecret()));
        try {
            DestroyResult result = client.post().uri("/image/destroy").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(DestroyResult.class);
            // "not found" means it is already gone, which is the state the caller wants.
            if (result == null || !("ok".equals(result.result()) || "not found".equals(result.result()))) {
                throw unavailable("Cloudinary delete returned " + (result == null ? "nothing" : result.result()), null);
            }
        } catch (RestClientException error) {
            throw unavailable("Cloudinary delete failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private static ResponseStatusException unavailable(String logMessage, Throwable cause) {
        log.error(logMessage);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên, vui lòng thử lại", cause);
    }
}
