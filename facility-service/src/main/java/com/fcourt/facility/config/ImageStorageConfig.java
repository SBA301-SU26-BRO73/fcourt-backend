package com.fcourt.facility.config;

import com.fcourt.facility.integration.CloudinaryImageStorage;
import com.fcourt.facility.service.ImageStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chooses the image host at startup. Without Cloudinary credentials the service still starts and
 * every upload/delete answers 503 with a clear message (never a crash, never a silent skip).
 */
@Configuration
class ImageStorageConfig {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageConfig.class);

    @Bean
    ImageStorage imageStorage(@Value("${facility.images.cloudinary.url:}") String url,
                              @Value("${facility.images.cloudinary.cloud-name:}") String cloudName,
                              @Value("${facility.images.cloudinary.api-key:}") String apiKey,
                              @Value("${facility.images.cloudinary.api-secret:}") String apiSecret,
                              @Value("${facility.images.cloudinary.base-url:https://api.cloudinary.com}") String baseUrl) {
        var credentials = new CloudinaryImageStorage.Credentials(cloudName, apiKey, apiSecret);
        if (!credentials.complete()) credentials = CloudinaryImageStorage.Credentials.fromUrl(url);
        if (!credentials.complete()) {
            log.warn("Cloudinary is not configured (set CLOUDINARY_URL or CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET): "
                    + "branch image upload is disabled");
            return new ImageStorage() {
                @Override
                public Stored store(String folder, Upload upload) {
                    throw notConfigured();
                }

                @Override
                public void delete(String publicId) {
                    throw notConfigured();
                }
            };
        }
        return new CloudinaryImageStorage(credentials, baseUrl, Clock.systemUTC());
    }

    private static ResponseStatusException notConfigured() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Image storage is not configured on this server");
    }
}
