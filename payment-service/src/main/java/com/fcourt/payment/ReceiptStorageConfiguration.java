package com.fcourt.payment;

import java.net.URI;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 adapter with the default AWS credential chain (EKS Pod Identity in the cluster, a profile or the
 * AWS_* variables locally); no access key is configured. Without {@code storage.s3.bucket} the service still starts
 * and uploads answer 503.
 */
@Configuration
class ReceiptStorageConfiguration {
    @Bean
    ReceiptStorage receiptStorage(@Value("${storage.s3.bucket:}") String bucket,
            @Value("${storage.s3.region:ap-southeast-1}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.path-style:false}") boolean pathStyle,
            @Value("${storage.s3.presign-ttl:10m}") Duration presignTtl) {
        if (bucket.isBlank()) {
            LoggerFactory.getLogger(getClass()).warn("Receipt storage is not configured (set STORAGE_S3_BUCKET): uploads are disabled");
            return bytes -> { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt storage unavailable"); };
        }
        boolean pathAccess = pathStyle || !endpoint.isBlank();
        var s3 = S3Client.builder().region(Region.of(region)).forcePathStyle(pathAccess);
        var presigner = S3Presigner.builder().region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathAccess).build());
        if (!endpoint.isBlank()) {
            s3.endpointOverride(URI.create(endpoint));
            presigner.endpointOverride(URI.create(endpoint));
        }
        return new S3ReceiptStorage(s3.build(), presigner.build(), bucket, presignTtl);
    }
}
