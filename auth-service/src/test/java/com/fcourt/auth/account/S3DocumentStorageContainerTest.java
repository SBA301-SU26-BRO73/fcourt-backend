package com.fcourt.auth.account;

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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** The Spring configuration builds the adapter exactly as in a local run, against a MinIO S3 (no AWS account). */
@Testcontainers
class S3DocumentStorageContainerTest {
    static final String BUCKET = "fcourt-auth-test";
    static final String REGION = "us-east-1";
    static final byte[] PDF = "%PDF-1.4 sample".getBytes();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @Container
    static final MinIOContainer S3 =
            new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio@sha256:a1ac4589a5f8136b616e667b48a0bea9719bb10bd45799ea946f24bbedfafde4").asCompatibleSubstituteFor("minio/minio"));

    static S3Client admin;
    static DocumentStorage storage;

    @BeforeAll
    static void setUp() {
        // The default credential chain reads these; LocalStack accepts any value.
        System.setProperty("aws.accessKeyId", S3.getUserName());
        System.setProperty("aws.secretAccessKey", S3.getPassword());
        admin = S3Client.builder().endpointOverride(URI.create(S3.getS3URL())).region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3.getUserName(), S3.getPassword()))).build();
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new DocumentStorageConfiguration().documentStorage(BUCKET, REGION,
                URI.create(S3.getS3URL()).toString(), "", false, Duration.ofMinutes(10), "");
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void privateDocumentRoundTripsThroughAPresignedUrlAndIsDeleted() throws Exception {
        String key = storage.storePrivate(PDF, "owner-documents/legal/9");

        var head = admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(head.contentType()).isEqualTo("application/pdf");
        assertThat(head.cacheControl()).isEqualTo(S3DocumentStorage.PRIVATE_CACHE);

        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(storage.signedUrl(key))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);

        storage.delete(key);
        try {
            admin.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build()).close();
            throw new AssertionError("object should be gone");
        } catch (NoSuchKeyException expected) {
            // deleted
        }
    }

    @Test
    void publicAvatarIsStoredWithImageHeadersAndUrlPointsAtEndpointAndBucket() {
        String key = storage.storePublic(PNG, "avatars/7");

        var head = admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.cacheControl()).isEqualTo(S3DocumentStorage.PUBLIC_CACHE);
        assertThat(storage.publicUrl(key)).isEqualTo(URI.create(S3.getS3URL()) + "/" + BUCKET + "/" + key);
    }
}
