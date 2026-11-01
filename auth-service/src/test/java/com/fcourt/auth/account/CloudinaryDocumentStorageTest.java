package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fcourt.auth.account.CloudinaryDocumentStorage.Asset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudinaryDocumentStorageTest {
    @Test
    void parsesPublicIdAndResourceTypeFromADeliveryUrl() {
        assertThat(Asset.parse("https://res.cloudinary.com/demo/image/upload/v1730000000/fcourt/legal/9/abc-123.pdf"))
                .contains(new Asset("image", "fcourt/legal/9/abc-123"));
        assertThat(Asset.parse("https://res.cloudinary.com/demo/image/upload/fcourt/court/9/def.jpg"))
                .contains(new Asset("image", "fcourt/court/9/def"));
        // Raw files keep the extension as part of the public id.
        assertThat(Asset.parse("https://res.cloudinary.com/demo/raw/upload/v1/fcourt/legal/9/ghi.pdf"))
                .contains(new Asset("raw", "fcourt/legal/9/ghi.pdf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://res.cloudinary.com/demo/image/upload/v1/a.jpg",
            "https://evil.example/demo/image/upload/v1/a.jpg", "https://res.cloudinary.com/demo/image/upload/v1",
            "https://res.cloudinary.com/demo/image/fetch/a.jpg", "not a url"})
    void refusesUrlsThatAreNotCloudinaryUploads(String url) {
        assertThat(Asset.parse(url)).isEmpty();
    }

    @Test
    void deleteWithoutConfigurationOnlyLogs() {
        var storage = new CloudinaryDocumentStorage("");

        assertThatCode(() -> storage.delete("https://res.cloudinary.com/demo/image/upload/v1/a.jpg"))
                .doesNotThrowAnyException();
    }
}
