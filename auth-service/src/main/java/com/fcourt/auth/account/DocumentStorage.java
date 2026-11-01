package com.fcourt.auth.account;

/** Stores an uploaded court owner document and returns its public HTTPS URL. */
interface DocumentStorage {
    String store(byte[] content, String folder);

    /** Best effort: removes a file returned by {@link #store}; never throws, failures are only logged. */
    void delete(String url);
}
