package com.fcourt.auth.account;

import com.fcourt.auth.common.ApiException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 adapter of {@link DocumentStorage}. The bucket is private: public files are served from
 * {@code publicBaseUrl} (CloudFront or LocalStack), private ones only through presigned GET URLs.
 */
class S3DocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);
    private static final String UNAVAILABLE = "Document storage is unavailable";
    /** Keys carry a random UUID and are never overwritten, so a cached public object can never go stale. */
    static final String PUBLIC_CACHE = "public, max-age=31536000, immutable";
    static final String PRIVATE_CACHE = "private, no-store";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBaseUrl;
    private final Duration presignTtl;

    S3DocumentStorage(S3Client s3, S3Presigner presigner, String bucket, String publicBaseUrl, Duration presignTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.presignTtl = presignTtl;
    }

    @Override
    public String storePublic(byte[] content, String folder) {
        return put(content, folder, PUBLIC_CACHE);
    }

    @Override
    public String storePrivate(byte[] content, String folder) {
        return put(content, folder, PRIVATE_CACHE);
    }

    private String put(byte[] content, String folder, String cacheControl) {
        var type = StoredFileType.sniff(content)
                .orElseThrow(() -> ApiException.badRequest("Accepted file types are JPEG, PNG, WebP and PDF"));
        String key = folder + "/" + UUID.randomUUID() + "." + type.extension();
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type.contentType())
                    .cacheControl(cacheControl).build(), RequestBody.fromBytes(content));
            return key;
        } catch (RuntimeException error) {
            // Only the type is logged: SDK messages can echo request details.
            log.error("Document upload failed: {}", error.getClass().getSimpleName());
            throw ApiException.unavailable(UNAVAILABLE);
        }
    }

    @Override
    public String publicUrl(String stored) {
        if (stored == null || isLegacyUrl(stored)) return stored;
        return publicBaseUrl + "/" + stored;
    }

    @Override
    public String signedUrl(String stored) {
        if (stored == null || isLegacyUrl(stored)) return stored;
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(presignTtl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(stored).build()).build())
                    .url().toString();
        } catch (RuntimeException error) {
            log.error("Could not presign document {}: {}", stored, error.getClass().getSimpleName());
            throw ApiException.unavailable(UNAVAILABLE);
        }
    }

    @Override
    public void delete(String stored) {
        if (stored == null) return;
        if (isLegacyUrl(stored)) {
            log.warn("Legacy file left in place, not hosted by S3: {}", stored);
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(stored).build());
        } catch (RuntimeException error) {
            log.warn("Could not delete {} from document storage: {}", stored, error.getClass().getSimpleName());
        }
    }

    static boolean isLegacyUrl(String stored) {
        return stored.startsWith("https://") || stored.startsWith("http://");
    }
}
