package com.fcourt.discovery.config;

import com.fcourt.discovery.component.CloudinaryReviewImageStorage;
import com.fcourt.discovery.component.ReviewImagePolicy;
import com.fcourt.discovery.component.ReviewImageStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Without Cloudinary credentials the service still starts; uploads answer 503 and review photos are rejected. */
@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
class ReviewImageConfig {
    private static final Logger log = LoggerFactory.getLogger(ReviewImageConfig.class);

    private static CloudinaryReviewImageStorage.Credentials credentials(String url, String cloudName, String apiKey, String apiSecret) {
        var credentials = new CloudinaryReviewImageStorage.Credentials(cloudName, apiKey, apiSecret);
        return credentials.complete() ? credentials : CloudinaryReviewImageStorage.Credentials.fromUrl(url);
    }

    @Bean
    ReviewImagePolicy reviewImagePolicy(@Value("${discovery.reviews.images.cloudinary.url:}") String url,
            @Value("${discovery.reviews.images.cloudinary.cloud-name:}") String cloudName,
            @Value("${discovery.reviews.images.cloudinary.api-key:}") String apiKey,
            @Value("${discovery.reviews.images.cloudinary.api-secret:}") String apiSecret) {
        var credentials = credentials(url, cloudName, apiKey, apiSecret);
        // The allowlist only needs the cloud name; uploads additionally need the key and secret.
        return new ReviewImagePolicy(credentials.complete() ? credentials.cloudName() : cloudName);
    }

    @Bean
    ReviewImageStorage reviewImageStorage(@Value("${discovery.reviews.images.cloudinary.url:}") String url,
            @Value("${discovery.reviews.images.cloudinary.cloud-name:}") String cloudName,
            @Value("${discovery.reviews.images.cloudinary.api-key:}") String apiKey,
            @Value("${discovery.reviews.images.cloudinary.api-secret:}") String apiSecret,
            @Value("${discovery.reviews.images.cloudinary.base-url:https://api.cloudinary.com}") String baseUrl) {
        var credentials = credentials(url, cloudName, apiKey, apiSecret);
        if (!credentials.complete()) {
            log.warn("Cloudinary is not configured (set CLOUDINARY_URL or CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET): review photo upload is disabled");
            return (folder, upload) -> {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình lưu trữ ảnh trên máy chủ");
            };
        }
        return new CloudinaryReviewImageStorage(credentials, baseUrl, Clock.systemUTC());
    }
}
