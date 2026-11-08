package com.fcourt.discovery.component;

/** Remote image host for review photos (Cloudinary in production). Failures surface as ResponseStatusException. */
public interface ReviewImageStorage {
    /** A file already validated by {@link ReviewImageValidator}. */
    record Upload(String contentType, byte[] content) { }

    /** Stores the file under {@code folder} and returns its HTTPS URL. */
    String store(String folder, Upload upload);
}
