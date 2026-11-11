package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ReceiptStorageTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
    static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
    static final String LEGACY = "https://res.cloudinary.com/demo/image/upload/v1/fcourt/receipts/abc.png";

    S3Client s3;
    S3ReceiptStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        var presigner = S3Presigner.builder().region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret"))).build();
        storage = new S3ReceiptStorage(s3, presigner, "fcourt-test", Duration.ofMinutes(10));
    }

    @Test
    void receiptsGoUnderReceiptsWithTheTypeFromTheBytesAndNoCaching() {
        String key = storage.upload(PNG);

        assertThat(key).matches("receipts/[0-9a-f-]{36}\\.png");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3ReceiptStorage.CACHE_CONTROL);
    }

    @Test
    void refundProofsGoUnderTheirOwnPrefix() {
        assertThat(storage.uploadRefundProof(JPG)).matches("refund-proofs/[0-9a-f-]{36}\\.jpg");
    }

    @Test
    void anythingButJpegOrPngIsRejectedBeforeUpload() {
        assertThatThrownBy(() -> storage.upload("<svg/>".getBytes())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void uploadFailureIs503WithoutDetail() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("secret").build());

        assertThatThrownBy(() -> storage.upload(PNG)).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(503);
            assertThat(e.getReason()).isEqualTo("Receipt storage unavailable");
        });
    }

    @Test
    void signedUrlIsAPresignedGetAndLegacyUrlsAndNullPassThrough() {
        String url = storage.signedUrl("receipts/abc.png");

        assertThat(url).startsWith("https://fcourt-test.s3.ap-southeast-1.amazonaws.com/receipts/abc.png?")
                .contains("X-Amz-Signature=").contains("X-Amz-Expires=600");
        assertThat(storage.signedUrl(LEGACY)).isEqualTo(LEGACY);
        assertThat(storage.signedUrl(null)).isNull();
    }

    @Test
    void deleteUsesTheKeySkipsLegacyAndNeverThrows() {
        storage.delete("receipts/abc.png");
        var request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("receipts/abc.png");

        storage.delete(LEGACY);
        storage.delete(null);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("x").build());
        storage.delete("receipts/def.png");
    }

    @Test
    void responsesCarryPresignedUrlsWhileKeysStayInTheRecords() {
        var urls = new ReceiptUrls(new ReceiptStorage() {
            @Override public String upload(byte[] bytes) { return null; }
            @Override public String signedUrl(String stored) { return "https://signed.test/" + stored; }
        });
        var payment = new PaymentRecord(1, 2, 3, BigDecimal.TEN, "PENDING", "receipts/a.png", Instant.EPOCH, null, null, null, null);
        assertThat(urls.view(payment).receiptImageUrl()).isEqualTo("https://signed.test/receipts/a.png");
        assertThat(payment.receiptImageUrl()).isEqualTo("receipts/a.png");
        var noReceipt = new PaymentRecord(1, 2, 3, BigDecimal.TEN, "PENDING", null, null, null, null, null, null);
        assertThat(urls.view(noReceipt)).isSameAs(noReceipt);
        var refund = new RefundView(1, 2, BigDecimal.TEN, "COMPLETED", null, null, null, null, null, null, "refund-proofs/p.png", Instant.EPOCH, Instant.EPOCH);
        assertThat(urls.view(refund).transferProofUrl()).isEqualTo("https://signed.test/refund-proofs/p.png");
    }
}
