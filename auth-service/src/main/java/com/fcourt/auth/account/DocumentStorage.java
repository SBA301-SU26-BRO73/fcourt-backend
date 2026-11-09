package com.fcourt.auth.account;

/**
 * Port for files auth-service hosts: public avatars and private court owner documents. The adapter returns the
 * object key (for example {@code avatars/5/<uuid>.png}), which is what the database stores; URLs are derived from it
 * on read. A stored value that is already an absolute URL (Google photo, rows created before the S3 move) is
 * returned unchanged and never deleted remotely.
 */
public interface DocumentStorage {
    /** Stores a file served publicly through {@code storage.public-base-url} and returns its key. */
    String storePublic(byte[] content, String folder);

    /** Stores a file that is never public (read only through {@link #signedUrl}) and returns its key. */
    String storePrivate(byte[] content, String folder);

    /** Public URL of a key stored with {@link #storePublic}; an absolute URL is returned as is. */
    String publicUrl(String stored);

    /** Short-lived presigned GET URL of a key stored with {@link #storePrivate}; an absolute URL is returned as is. */
    String signedUrl(String stored);

    /** Best effort: removes a key returned by a store method; never throws, failures are only logged. */
    void delete(String stored);
}
