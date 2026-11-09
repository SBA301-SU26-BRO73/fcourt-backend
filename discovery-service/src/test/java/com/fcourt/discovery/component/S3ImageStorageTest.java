package com.fcourt.discovery.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

class S3ImageStorageTest {
    final S3Client s3 = mock(S3Client.class);
    final S3ImageStorage storage = new S3ImageStorage(s3, "fcourt-test", "https://cdn.example.test");

    @Test
    void uploadUsesTheNeutralKeyAsIsWithContentTypeAndImmutableCaching() {
        String url = storage.upload("reviews/10/abc.jpg", "image/jpeg", new byte[] {1});

        assertThat(url).isEqualTo("https://cdn.example.test/reviews/10/abc.jpg");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("fcourt-test");
        assertThat(request.getValue().key()).isEqualTo("reviews/10/abc.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3ImageStorage.CACHE_CONTROL);
        assertThat(storage.publicBaseUrl()).isEqualTo("https://cdn.example.test/");
        assertThat(storage.keyPrefix()).isEmpty();
    }

    @Test
    void uploadFailureIsABadGatewayWithoutDetail() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("secret").build());

        assertThatThrownBy(() -> storage.upload("reviews/10/a.jpg", "image/jpeg", new byte[] {1}))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(502);
                    assertThat(error.getReason()).doesNotContain("secret");
                });
    }

    @Test
    void deleteRemovesTheKeyButSkipsLegacyCloudinaryValues() {
        storage.delete("reviews/10/abc.jpg");
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("reviews/10/abc.jpg");

        var legacy = mock(S3Client.class);
        var legacyStorage = new S3ImageStorage(legacy, "fcourt-test", "https://cdn.example.test");
        legacyStorage.delete("fcourt/reviews/10/old");
        legacyStorage.delete("https://res.cloudinary.com/demo/image/upload/v1/fcourt/reviews/10/old.jpg");
        verifyNoInteractions(legacy);
    }

    @Test
    void listReturnsOnlyObjectsOlderThanTheCutoffUpToMax() {
        Instant cutoff = Instant.parse("2026-11-01T00:00:00Z");
        var page = software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder().contents(List.of(
                S3Object.builder().key("reviews/1/old1.jpg").lastModified(cutoff.minusSeconds(10)).build(),
                S3Object.builder().key("reviews/1/new.jpg").lastModified(cutoff.plusSeconds(10)).build(),
                S3Object.builder().key("reviews/1/old2.jpg").lastModified(cutoff.minusSeconds(20)).build())).build();
        var iterable = mock(ListObjectsV2Iterable.class);
        when(iterable.iterator()).thenAnswer(call -> List.of(page).iterator());
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(iterable);

        assertThat(storage.listByPrefix("reviews/", cutoff, 10)).extracting(ImageStorage.StoredObject::key)
                .containsExactly("reviews/1/old1.jpg", "reviews/1/old2.jpg");
        assertThat(storage.listByPrefix("reviews/", cutoff, 1)).hasSize(1);
        assertThat(storage.supportsCleanup()).isTrue();
    }
}
