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

    private static ResponseStatusException unavailable(String logMessage, Throwable cause) {
        log.error(logMessage);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên, vui lòng thử lại", cause);
    }
}
