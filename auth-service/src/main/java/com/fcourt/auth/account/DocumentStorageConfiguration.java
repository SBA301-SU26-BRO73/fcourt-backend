package com.fcourt.auth.account;

import com.fcourt.auth.common.ApiException;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 adapter with the default AWS credential chain (EKS Pod Identity in the cluster, a profile or the
 * AWS_* variables locally); no access key is ever configured here. Without {@code storage.s3.bucket} the service
 * still starts: uploads answer 503 and stored legacy URLs keep rendering.
 */
@Configuration
class DocumentStorageConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DocumentStorageConfiguration.class);

    @Bean
    DocumentStorage documentStorage(@Value("${storage.s3.bucket:}") String bucket,
                                    @Value("${storage.s3.region:ap-southeast-1}") String region,
                                    @Value("${storage.s3.endpoint:}") String endpoint,
                                    @Value("${storage.s3.presign-endpoint:}") String presignEndpoint,
                                    @Value("${storage.s3.path-style:false}") boolean pathStyle,
                                    @Value("${storage.s3.presign-ttl:10m}") Duration presignTtl,
                                    @Value("${storage.public-base-url:}") String publicBaseUrl) {
        if (bucket.isBlank()) {
            log.warn("Document storage is not configured (set STORAGE_S3_BUCKET): file upload is disabled");
            return new UnconfiguredDocumentStorage();
        }
        boolean pathAccess = pathStyle || !endpoint.isBlank();
        var s3 = S3Client.builder().region(Region.of(region)).forcePathStyle(pathAccess);
        var presigner = S3Presigner.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathAccess).build());
        if (!endpoint.isBlank()) {
            s3.endpointOverride(URI.create(endpoint));
            presigner.endpointOverride(URI.create(presignEndpoint.isBlank() ? endpoint : presignEndpoint));
        }
        String base = !publicBaseUrl.isBlank() ? publicBaseUrl
                : !endpoint.isBlank() ? endpoint.replaceAll("/+$", "") + "/" + bucket
                : "https://" + bucket + ".s3." + region + ".amazonaws.com";
        return new S3DocumentStorage(s3.build(), presigner.build(), bucket, base, presignTtl);
    }

    /** Keeps the service bootable without a bucket; stored legacy URLs are still passed through. */
    static class UnconfiguredDocumentStorage implements DocumentStorage {
        @Override public String storePublic(byte[] content, String folder) { throw unavailable(); }
        @Override public String storePrivate(byte[] content, String folder) { throw unavailable(); }
        @Override public String publicUrl(String stored) { return stored; }
        @Override public String signedUrl(String stored) { return stored; }
        @Override public void delete(String stored) { log.warn("Orphaned upload left in document storage: {}", stored); }

        private static ApiException unavailable() { return ApiException.unavailable("Document storage is unavailable"); }
    }
}
