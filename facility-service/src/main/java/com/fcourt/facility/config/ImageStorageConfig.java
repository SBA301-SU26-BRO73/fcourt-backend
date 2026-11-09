package com.fcourt.facility.config;

import com.fcourt.facility.integration.S3ImageStorage;
import com.fcourt.facility.service.ImageStorage;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds the S3 image adapter with the default AWS credential chain (EKS Pod Identity in the cluster, a profile or
 * the AWS_* variables locally); no access key is configured. Without {@code storage.s3.bucket} the service still
 * starts and every upload/delete answers 503 with a clear message (never a crash, never a silent skip).
 */
@Configuration
class ImageStorageConfig {
    private static final Logger log = LoggerFactory.getLogger(ImageStorageConfig.class);

    @Bean
    ImageStorage imageStorage(@Value("${storage.s3.bucket:}") String bucket,
                              @Value("${storage.s3.region:ap-southeast-1}") String region,
                              @Value("${storage.s3.endpoint:}") String endpoint,
                              @Value("${storage.s3.path-style:false}") boolean pathStyle,
                              @Value("${storage.public-base-url:}") String publicBaseUrl) {
        if (bucket.isBlank()) {
            log.warn("Image storage is not configured (set STORAGE_S3_BUCKET): branch image upload is disabled");
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
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle || !endpoint.isBlank());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        String base = !publicBaseUrl.isBlank() ? publicBaseUrl
                : !endpoint.isBlank() ? endpoint.replaceAll("/+$", "") + "/" + bucket
                : "https://" + bucket + ".s3." + region + ".amazonaws.com";
        return new S3ImageStorage(builder.build(), bucket, base);
    }

    private static ResponseStatusException notConfigured() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Image storage is not configured on this server");
    }
}
