package com.fcourt.discovery.component;

import java.time.Instant;
import java.util.List;

/**
 * Port for hosting images, independent of the provider. Cloudinary is one adapter ({@link CloudinaryImageStorage});
 * another host (for example S3 behind a CDN) only needs its own adapter and configuration.
 *
 * <p>Keys are provider-neutral relative paths such as {@code reviews/{userId}/{uuid}.jpg}. An adapter may add its own
 * root folder ({@link #keyPrefix()}) to the public URL but never changes the key part.
 */
public interface ImageStorage {
    /** One hosted object. {@code key} is the identifier to pass to {@link #delete}; it may lack the file extension. */
    record StoredObject(String key, Instant createdAt) { }

    /** Stores the content under {@code key} and returns its public HTTPS URL. Failures are ResponseStatusException. */
    String upload(String key, String contentType, byte[] content);

    /** Idempotent: deleting an object that is already gone succeeds. */
    default void delete(String key) { throw new UnsupportedOperationException("deleting is not supported"); }

    /** Objects under {@code prefix} created before {@code olderThan}, at most {@code max}. */
    default List<StoredObject> listByPrefix(String prefix, Instant olderThan, int max) {
        throw new UnsupportedOperationException("listing is not supported");
    }

    /** False when the adapter cannot list and delete (or is not configured): cleanup then does nothing. */
    default boolean supportsCleanup() { return false; }

    /** Every URL this adapter issues starts with this HTTPS base; empty when the adapter is not configured. */
    String publicBaseUrl();

    /** Folder the adapter puts in front of the key inside the public URL (for example {@code fcourt/}); may be empty. */
    default String keyPrefix() { return ""; }
}
