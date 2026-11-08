package com.fcourt.discovery.component;

import java.time.Instant;
import java.util.List;

/** Remote image host for review photos (Cloudinary in production). Failures surface as ResponseStatusException. */
public interface ReviewImageStorage {
    /** A file already validated by {@link ReviewImageValidator}. */
    record Upload(String contentType, byte[] content) { }

    /** One hosted file as listed by the host; {@code publicId} has no extension or version. */
    record StoredAsset(String publicId, String url, Instant createdAt) { }

    /** One page of a listing; {@code nextCursor} is null on the last page. */
    record AssetPage(List<StoredAsset> assets, String nextCursor) { }

    /** Stores the file under {@code folder} and returns its HTTPS URL. */
    String store(String folder, Upload upload);

    /** False when the host is not configured: cleanup then does nothing. */
    default boolean supportsCleanup() { return false; }

    /** Lists files whose public id starts with {@code prefix}. */
    default AssetPage list(String prefix, String cursor) { throw new UnsupportedOperationException("listing is not configured"); }

    /** Idempotent: deleting a file that is already gone succeeds. */
    default void delete(String publicId) { throw new UnsupportedOperationException("deleting is not configured"); }
}
