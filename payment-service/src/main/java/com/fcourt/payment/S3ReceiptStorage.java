package com.fcourt.payment;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** S3 adapter: a private bucket, evidence is readable only through short-lived presigned GET URLs. */
class S3ReceiptStorage implements ReceiptStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ReceiptStorage.class);
    static final String RECEIPTS = "receipts/";
    static final String REFUND_PROOFS = "refund-proofs/";
    static final String CACHE_CONTROL = "private, no-store";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;

    S3ReceiptStorage(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    @Override
    public String upload(byte[] bytes) {
        return put(RECEIPTS, bytes);
    }

    @Override
    public String uploadRefundProof(byte[] bytes) {
        return put(REFUND_PROOFS, bytes);
    }

    private String put(String prefix, byte[] bytes) {
        String type = contentType(bytes);
        if (type == null) throw ReceiptValidator.invalid();
        String key = prefix + UUID.randomUUID() + (type.equals("image/png") ? ".png" : ".jpg");
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type)
                    .cacheControl(CACHE_CONTROL).build(), RequestBody.fromBytes(bytes));
            return key;
        } catch (RuntimeException e) {
            // Never include SDK error details.
            log.error("Receipt upload failed: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    @Override
    public String signedUrl(String stored) {
        if (stored == null || isLegacyUrl(stored)) return stored;
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(presignTtl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(stored).build()).build()).url().toString();
        } catch (RuntimeException e) {
            log.error("Could not presign {}: {}", stored, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    @Override
    public void delete(String stored) {
        if (stored == null) return;
        if (isLegacyUrl(stored)) {
            log.warn("Legacy receipt left in place, not hosted by S3: {}", stored);
            return;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(stored).build());
        } catch (RuntimeException e) {
            log.warn("Receipt cleanup failed for {}, object left for the lifecycle rule", stored);
        }
    }

    static String contentType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        return null;
    }

    static boolean isLegacyUrl(String stored) {
        return stored.startsWith("https://") || stored.startsWith("http://");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt storage unavailable");
    }
}
