package com.fcourt.discovery.component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Accepts only real JPEG/PNG/WebP files: the type is read from the content, not from the client headers. */
@Component
public class ReviewImageValidator {
    public static final int MAX_FILES = 5;

    /** A file whose type was detected from its content. */
    public record ValidatedImage(String contentType, byte[] content) {
        public String extension() {
            return switch (contentType) { case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "jpg"; };
        }
    }
    private final long maxBytes;

    public ReviewImageValidator(@Value("${discovery.reviews.images.max-file-bytes:5242880}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public ReviewImageValidator.ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("Tệp ảnh trống");
        if (file.getSize() > maxBytes) throw bad("Ảnh vượt quá dung lượng tối đa " + maxBytes / (1024 * 1024) + " MB");
        byte[] content;
        try { content = file.getBytes(); }
        catch (IOException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được tệp tải lên", error); }
        String type = detect(content);
        if (type == null) throw bad("Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP");
        return new ReviewImageValidator.ValidatedImage(type, content);
    }

    public List<ReviewImageValidator.ValidatedImage> validateAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw bad("Cần ít nhất một ảnh");
        if (files.size() > MAX_FILES) throw bad("Tối đa " + MAX_FILES + " ảnh mỗi lần tải lên");
        return files.stream().map(this::validate).toList();
    }

    static String detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
