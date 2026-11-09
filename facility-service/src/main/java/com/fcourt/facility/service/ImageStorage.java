package com.fcourt.facility.service;

/** Remote image host (S3 behind a public base URL). Failures surface as ResponseStatusException. */
public interface ImageStorage {
    /** A file already validated by {@link ImageUploadValidator}. */
    record Upload(String filename, String contentType, byte[] content) { }

    record Stored(String url, String publicId) { }

    Stored store(String folder, Upload upload);

    void delete(String publicId);
}
