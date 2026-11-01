package com.fcourt.payment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class ReceiptValidator {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private ReceiptValidator() { }
    static byte[] read(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
        try {
            byte[] bytes = file.getBytes();
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) throw invalid();
                    reader.setInput(input);
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels <= 0 || pixels > 25_000_000 || reader.read(0) == null) throw invalid();
                } finally { reader.dispose(); }
            }
            return bytes;
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }
    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt must be a valid JPEG/PNG up to 5MB and 25 megapixels");
    }
}
