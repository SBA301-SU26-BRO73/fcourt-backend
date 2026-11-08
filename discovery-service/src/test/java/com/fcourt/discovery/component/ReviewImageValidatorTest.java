package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ReviewImageValidatorTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
    final ReviewImageValidator validator = new ReviewImageValidator(1024);

    static MockMultipartFile file(String name, String declaredType, byte[] content) {
        return new MockMultipartFile("files", name, declaredType, content);
    }

    @Test
    void detectsTypeFromContentNotFromDeclaredHeaderOrName() {
        assertThat(validator.validate(file("a.txt", "text/plain", PNG)).contentType()).isEqualTo("image/png");
        assertThat(validator.validate(file("a.png", "image/png", JPEG)).contentType()).isEqualTo("image/jpeg");
        assertThat(validator.validate(file("../../a.exe", "application/x-msdownload", WEBP)).contentType()).isEqualTo("image/webp");
    }

    @Test
    void rejectsSpoofedNonImagesEmptyAndOversizedFiles() {
        for (byte[] content : List.of("<svg onload=alert(1)/>".getBytes(), "GIF89a....".getBytes(), "%PDF-1.4".getBytes()))
            assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", content)))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("JPEG, PNG");
        assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", new byte[0]))).hasMessageContaining("trống");
        byte[] big = new byte[2048];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThatThrownBy(() -> validator.validate(file("a.png", "image/png", big))).hasMessageContaining("dung lượng");
    }

    @Test
    void enforcesFileCount() {
        assertThatThrownBy(() -> validator.validateAll(List.of())).hasMessageContaining("ít nhất");
        assertThatThrownBy(() -> validator.validateAll(null)).hasMessageContaining("ít nhất");
        assertThat(validator.validateAll(Collections.nCopies(5, file("a.png", "image/png", PNG)))).hasSize(5);
        assertThatThrownBy(() -> validator.validateAll(Collections.nCopies(6, file("a.png", "image/png", PNG))))
                .hasMessageContaining("Tối đa 5");
    }
}
