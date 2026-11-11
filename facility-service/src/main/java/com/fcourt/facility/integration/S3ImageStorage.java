package com.fcourt.facility.integration;

import com.fcourt.facility.service.ImageStorage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 adapter for the branch gallery. The bucket is private and objects are read through {@code publicBaseUrl}
 * (CloudFront, or the LocalStack URL locally). The key {@code branches/{branchId}/{uuid}.{ext}} is stored as
 * {@code public_id}; {@code url} is {@code publicBaseUrl/key}. Rows created before the S3 move hold a Cloudinary
 * public id (for example {@code fcourt/branches/3/x}): they keep rendering from their stored url and are never
 * deleted remotely.
 */
public class S3ImageStorage implements ImageStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ImageStorage.class);
    /** Keys carry a random UUID and are never overwritten, so a cached object can never go stale. */
    public static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    static final String KEY_ROOT = "branches/";

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public S3ImageStorage(S3Client s3, String bucket, String publicBaseUrl) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    @Override
    public Stored store(String folder, Upload upload) {
        // The client file name is never used: the key is server-generated, the type was read from the bytes.
        String key = folder + "/" + UUID.randomUUID() + "." + extension(upload.contentType());
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(upload.contentType())
                    .cacheControl(CACHE_CONTROL).build(), RequestBody.fromBytes(upload.content()));
            return new Stored(publicBaseUrl + "/" + key, key);
        } catch (RuntimeException error) {
            throw unavailable("S3 upload failed: " + error.getClass().getSimpleName(), error);
        }
    }

    @Override
    public void delete(String publicId) {
        if (!publicId.startsWith(KEY_ROOT)) {
            log.warn("Legacy image left in place, not hosted by S3: {}", publicId);
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(publicId).build()); // idempotent in S3
        } catch (RuntimeException error) {
            throw unavailable("S3 delete failed: " + error.getClass().getSimpleName(), error);
        }
    }

    static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static ResponseStatusException unavailable(String logMessage, Throwable cause) {
        log.error(logMessage);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image host request failed", cause);
    }
}
