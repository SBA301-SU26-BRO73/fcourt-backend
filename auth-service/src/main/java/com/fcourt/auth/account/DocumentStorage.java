package com.fcourt.auth.account;

/** Stores an uploaded court owner document and returns its public HTTPS URL. */
interface DocumentStorage {
    String store(byte[] content, String folder);
}
