package com.fcourt.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ImageUploadValidatorTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};

    final ImageUploadValidator validator = new ImageUploadValidator(1024);

    static MockMultipartFile file(String name, String declaredType, byte[] content) {
        return new MockMultipartFile("files", name, declaredType, content);
    }

    @Test
    void detectsTypeFromContentNotFromDeclaredHeader() {
        assertThat(validator.validate(file("a.png", "text/plain", PNG)).contentType()).isEqualTo("image/png");
        assertThat(validator.validate(file("a.jpg", "image/png", JPEG)).contentType()).isEqualTo("image/jpeg");
        assertThat(validator.validate(file("a.webp", "image/webp", WEBP)).contentType()).isEqualTo("image/webp");
    }

    @Test
    void rejectsNonImagesEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", "<svg/>".getBytes())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("JPEG, PNG or WebP");
        assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", new byte[0])))
                .hasMessageContaining("empty");
        byte[] big = new byte[2048];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", big)))
                .hasMessageContaining("maximum size");
    }

    @Test
    void requiresAtLeastOneFile() {
        assertThatThrownBy(() -> validator.validateAll(List.of())).hasMessageContaining("At least one");
        assertThatThrownBy(() -> validator.validateAll(null)).hasMessageContaining("At least one");
    }
}
