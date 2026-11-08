package com.fcourt.discovery.config;

import com.fcourt.discovery.component.CloudinaryImageStorage;
import com.fcourt.discovery.component.ImageStorage;
import com.fcourt.discovery.component.ReviewImagePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chooses the image host. {@code storage.provider} selects the adapter; only {@code cloudinary} exists today, and a
 * new host (for example S3 behind a CDN) needs one more {@link ImageStorage} adapter plus its own settings and
 * {@code storage.public-base-url}. Without credentials the service still starts: uploads answer 503 and review
 * photos are rejected.
 */
@Configuration
@EnableScheduling
class ImageStorageConfig {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageConfig.class);

    @Bean
    ImageStorage imageStorage(@Value("${storage.provider:cloudinary}") String provider,
            @Value("${storage.cloudinary.url:}") String url,
            @Value("${storage.cloudinary.cloud-name:}") String cloudName,
            @Value("${storage.cloudinary.api-key:}") String apiKey,
            @Value("${storage.cloudinary.api-secret:}") String apiSecret,
            @Value("${storage.cloudinary.base-url:https://api.cloudinary.com}") String apiBaseUrl) {
        if (!"cloudinary".equalsIgnoreCase(provider.trim()))
            throw new IllegalStateException("Unsupported storage.provider '" + provider + "' (only 'cloudinary' is implemented)");
        var credentials = new CloudinaryImageStorage.Credentials(cloudName, apiKey, apiSecret);
        if (!credentials.complete()) credentials = CloudinaryImageStorage.Credentials.fromUrl(url);
        if (!credentials.complete()) {
            log.warn("Image storage is not configured (Cloudinary: set CLOUDINARY_URL or CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET): review photo upload is disabled");
            return new ImageStorage() {
                @Override public String upload(String key, String contentType, byte[] content) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình lưu trữ ảnh trên máy chủ");
                }
                @Override public String publicBaseUrl() { return ""; }
                @Override public List<StoredObject> listByPrefix(String prefix, Instant olderThan, int max) { return List.of(); }
            };
        }
        return new CloudinaryImageStorage(credentials, apiBaseUrl, Clock.systemUTC());
    }

    /** {@code storage.public-base-url} overrides the base the adapter derives (needed for a CDN or custom domain). */
    @Bean
    ReviewImagePolicy reviewImagePolicy(ImageStorage storage, @Value("${storage.public-base-url:}") String override) {
        return new ReviewImagePolicy(override.isBlank() ? storage.publicBaseUrl() : override, storage.keyPrefix());
    }
}
