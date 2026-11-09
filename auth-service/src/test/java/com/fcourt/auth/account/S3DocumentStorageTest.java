package com.fcourt.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fcourt.auth.common.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3DocumentStorageTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
    static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};

    S3Client s3;
    S3DocumentStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        // Presigning is an offline computation, so a real presigner with dummy credentials is enough.
        var presigner = S3Presigner.builder().region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret"))).build();
        storage = new S3DocumentStorage(s3, presigner, "fcourt-test", "https://cdn.example.test/", Duration.ofMinutes(10));
    }

    @Test
    void publicFilesGetAKeyUnderTheFolderAndLongLivedCaching() {
        String key = storage.storePublic(PNG, "avatars/7");

        assertThat(key).matches("avatars/7/[0-9a-f-]{36}\\.png");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("fcourt-test");
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3DocumentStorage.PUBLIC_CACHE);
    }

    @Test
    void privateFilesAreNotCacheableAndTheTypeComesFromTheBytes() {
        String key = storage.storePrivate(PDF, "owner-documents/legal/9");

        assertThat(key).matches("owner-documents/legal/9/[0-9a-f-]{36}\\.pdf");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3DocumentStorage.PRIVATE_CACHE);
    }

    @Test
    void unrecognisedContentIsRejectedBeforeAnyUpload() {
        assertThatThrownBy(() -> storage.storePrivate(new byte[] {1, 2, 3}, "owner-documents/legal/9"))
                .isInstanceOf(ApiException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadFailureIsReportedAsUnavailableWithoutLeakingDetail() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("secret detail").build());

        assertThatThrownBy(() -> storage.storePublic(PNG, "avatars/7")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getMessage()).doesNotContain("secret").isEqualTo("Document storage is unavailable"));
    }

    @Test
    void publicUrlIsTheBaseUrlPlusKeyAndLegacyUrlsPassThrough() {
        assertThat(storage.publicUrl("avatars/7/a.png")).isEqualTo("https://cdn.example.test/avatars/7/a.png");
        assertThat(storage.publicUrl("https://lh3.googleusercontent.com/a.jpg")).isEqualTo("https://lh3.googleusercontent.com/a.jpg");
        assertThat(storage.publicUrl(null)).isNull();
    }

    @Test
    void signedUrlIsAShortLivedPresignedGetAndLegacyUrlsPassThrough() {
        String url = storage.signedUrl("owner-documents/legal/9/a.pdf");

        assertThat(url).startsWith("https://fcourt-test.s3.ap-southeast-1.amazonaws.com/owner-documents/legal/9/a.pdf?")
                .contains("X-Amz-Signature=").contains("X-Amz-Expires=600");
        String legacy = "https://res.cloudinary.com/fcourt-demo/image/upload/owner06/citizen-id.jpg";
        assertThat(storage.signedUrl(legacy)).isEqualTo(legacy);
    }

    @Test
    void deleteRemovesTheKeyButSkipsLegacyUrlsAndNeverThrows() {
        storage.delete("avatars/7/a.png");
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("avatars/7/a.png");

        storage.delete("https://res.cloudinary.com/demo/image/upload/v1/fcourt/avatars/7/old.png");
        storage.delete(null);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("boom").build());
        storage.delete("avatars/7/b.png");
    }

    @Test
    void sniffRecognisesTheAcceptedTypes() {
        assertThat(StoredFileType.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains(StoredFileType.JPEG);
        assertThat(StoredFileType.sniff(PNG)).contains(StoredFileType.PNG);
        assertThat(StoredFileType.sniff("RIFF1234WEBPxx".getBytes())).contains(StoredFileType.WEBP);
        assertThat(StoredFileType.sniff(PDF)).contains(StoredFileType.PDF);
        assertThat(StoredFileType.sniff("<html>".getBytes())).isEmpty();
    }

    @Test
    void unavailableMapsToHttp503() {
        assertThatThrownBy(() -> new DocumentStorageConfiguration.UnconfiguredDocumentStorage().storePrivate(PDF, "x"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
