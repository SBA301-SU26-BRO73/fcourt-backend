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
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** The Spring configuration builds the adapter as in a local run, against a LocalStack S3 (no AWS account). */
@Testcontainers
class S3ReceiptStorageLocalStackTest {
    static final String BUCKET = "fcourt-payment-test";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1")).withServices(LocalStackContainer.Service.S3);

    static S3Client admin;
    static ReceiptStorage storage;

    @BeforeAll
    static void setUp() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        admin = S3Client.builder().endpointOverride(LOCALSTACK.getEndpoint()).region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        admin.createBucket(b -> b.bucket(BUCKET));
        storage = new ReceiptStorageConfiguration().receiptStorage(BUCKET, LOCALSTACK.getRegion(),
                LOCALSTACK.getEndpoint().toString(), false, Duration.ofMinutes(10));
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
