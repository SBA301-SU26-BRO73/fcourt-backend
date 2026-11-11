package com.fcourt.discovery.component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 adapter for {@link ImageStorage}. The bucket is private; objects are read through
 * {@code storage.public-base-url} (CloudFront, or the LocalStack URL locally). The neutral key
 * {@code reviews/10/abc.jpg} is the S3 key as is, so the issued URL is {@code <public-base-url>/reviews/10/abc.jpg}.
 */
public class S3ImageStorage implements ImageStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);
    /** Keys carry a random UUID and are never overwritten, so a cached object can never go stale. */
    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public S3ImageStorage(S3Client s3, String bucket, String publicBaseUrl) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
    }

    @Override
    public String upload(String key, String contentType, byte[] content) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                    .cacheControl(CACHE_CONTROL).build(), RequestBody.fromBytes(content));
            return publicBaseUrl + key;
        } catch (RuntimeException error) {
            throw unavailable("S3 upload failed: " + error.getClass().getSimpleName(), error);
        }
    }

    @Override
    public boolean supportsCleanup() { return true; }

    @Override
    public void delete(String key) {
        if (key.startsWith("http://") || key.startsWith("https://") || key.startsWith("fcourt/")) {
            log.warn("Legacy image left in place, not hosted by S3: {}", key);
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); // idempotent in S3
        } catch (RuntimeException error) {
            throw unavailable("S3 delete failed: " + error.getClass().getSimpleName(), error);
        }
    }

    @Override
    public List<StoredObject> listByPrefix(String prefix, Instant olderThan, int max) {
        var found = new ArrayList<StoredObject>();
        try {
            var pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
            for (var page : pages) {
                for (var object : page.contents()) {
                    if (object.lastModified().isBefore(olderThan)) {
                        found.add(new StoredObject(object.key(), object.lastModified()));
                        if (found.size() >= max) return List.copyOf(found);
                    }
                }
            }
        } catch (RuntimeException error) {
            throw unavailable("S3 listing failed: " + error.getClass().getSimpleName(), error);
        }
        return List.copyOf(found);
    }

    @Override
    public String publicBaseUrl() { return publicBaseUrl; }

    private static ResponseStatusException unavailable(String logMessage, Throwable cause) {
        log.error(logMessage);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không thể tải ảnh lên, vui lòng thử lại", cause);
    }
}
