package com.fcourt.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** The Spring configuration builds the adapter as in a local run, against a MinIO (S3-compatible) container (no AWS account). */
@Testcontainers
class S3ReceiptStorageContainerTest {
    static final String BUCKET = "fcourt-payment-test";
    static final String REGION = "us-east-1";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @Container
    static final MinIOContainer S3 =
            new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio@sha256:a1ac4589a5f8136b616e667b48a0bea9719bb10bd45799ea946f24bbedfafde4").asCompatibleSubstituteFor("minio/minio"));

    static S3Client admin;
    static ReceiptStorage storage;

    @BeforeAll
    static void setUp() {
        System.setProperty("aws.accessKeyId", S3.getUserName());
        System.setProperty("aws.secretAccessKey", S3.getPassword());
        admin = S3Client.builder().endpointOverride(java.net.URI.create(S3.getS3URL())).region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3.getUserName(), S3.getPassword()))).build();
        admin.createBucket(b -> b.bucket(BUCKET));
        storage = new ReceiptStorageConfiguration().receiptStorage(BUCKET, REGION,
                java.net.URI.create(S3.getS3URL()).toString(), false, Duration.ofMinutes(10));
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void receiptRoundTripsThroughAPresignedUrlAndIsDeleted() throws Exception {
        String key = storage.upload(PNG);

        var head = admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.cacheControl()).isEqualTo(S3ReceiptStorage.CACHE_CONTROL);

        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(storage.signedUrl(key))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PNG);

        storage.delete(key);
        try {
            admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            throw new AssertionError("object should be gone");
        } catch (NoSuchKeyException expected) {
            // deleted
        }
    }

    @Test
    void refundProofIsStoredUnderItsOwnPrefix() {
        assertThat(storage.uploadRefundProof(PNG)).startsWith("refund-proofs/");
    }
}
