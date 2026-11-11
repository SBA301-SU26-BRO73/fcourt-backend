package com.fcourt.auth.account;

import java.util.Arrays;
import java.util.Optional;

/** File types auth-service accepts, recognised from the leading bytes and never from client-supplied headers. */
enum StoredFileType {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"), PDF("application/pdf", "pdf");

    private final String contentType;
    private final String extension;

    StoredFileType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    String contentType() { return contentType; }

    String extension() { return extension; }

    static Optional<StoredFileType> sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Optional.of(JPEG);
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return Optional.of(PNG);
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Optional.of(WEBP);
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return Optional.of(PDF);
        return Optional.empty();
    }
}
