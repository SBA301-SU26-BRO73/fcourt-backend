package com.fcourt.facility.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fcourt.facility.service.ImageStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3ImageStorageTest {
    final S3Client s3 = mock(S3Client.class);
    final S3ImageStorage storage = new S3ImageStorage(s3, "fcourt-test", "https://cdn.example.test/");

    @Test
    void storeBuildsAServerSideKeyAndPublicUrlIgnoringTheClientFileName() {
        var stored = storage.store("branches/3", new ImageStorage.Upload("../../evil name.php", "image/png", new byte[] {1}));

        assertThat(stored.publicId()).matches("branches/3/[0-9a-f-]{36}\\.png");
        assertThat(stored.url()).isEqualTo("https://cdn.example.test/" + stored.publicId());
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("fcourt-test");
        assertThat(request.getValue().key()).isEqualTo(stored.publicId());
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3ImageStorage.CACHE_CONTROL);
    }

    @Test
    void extensionFollowsTheDetectedContentType() {
        assertThat(S3ImageStorage.extension("image/jpeg")).isEqualTo("jpg");
        assertThat(S3ImageStorage.extension("image/png")).isEqualTo("png");
        assertThat(S3ImageStorage.extension("image/webp")).isEqualTo("webp");
    }

    @Test
    void uploadFailureIsABadGateway() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("x").build());

        assertThatThrownBy(() -> storage.store("branches/3", new ImageStorage.Upload("a.png", "image/png", new byte[] {1})))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void deleteUsesTheKeyAndSkipsLegacyCloudinaryPublicIds() {
        storage.delete("branches/3/abc.png");
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("branches/3/abc.png");

        var legacy = mock(S3Client.class);
        new S3ImageStorage(legacy, "b", "https://cdn.example.test").delete("fcourt/branches/3/old");
        verifyNoInteractions(legacy);
    }

    @Test
    void deleteFailureSurfacesSoTheCallerKeepsTheRowAndCanRetry() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("x").build());

        assertThatThrownBy(() -> storage.delete("branches/3/abc.png")).isInstanceOf(ResponseStatusException.class);
    }
}
