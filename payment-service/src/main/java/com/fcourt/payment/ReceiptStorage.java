package com.fcourt.payment;

/**
 * Private storage of payment evidence. Values kept in the database (and carried by events) are object keys such as
 * {@code receipts/<uuid>.png} or {@code refund-proofs/<uuid>.jpg}, never public URLs: clients receive a short-lived
 * presigned URL from {@link #signedUrl}. A stored value that is already an absolute URL (rows created before the S3
 * move) is returned unchanged and never deleted remotely.
 */
interface ReceiptStorage {
    /** Stores a payment receipt (already validated as JPEG/PNG) and returns its key. */
    String upload(byte[] bytes);

    /** Stores a refund transfer proof and returns its key. */
    default String uploadRefundProof(byte[] bytes) { return upload(bytes); }

    /** Presigned GET URL for a stored key; absolute legacy URLs and null pass through. */
    default String signedUrl(String stored) { return stored; }

    /** Best effort: never throws, failures are only logged. */
    default void delete(String stored) { }
}
