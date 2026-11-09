package com.fcourt.facility.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fcourt.facility.service.ImageStorage;
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

/** The adapter against a MinIO (S3-compatible) container (no AWS account needed). */
@Testcontainers
class S3ImageStorageContainerTest {
    static final String BUCKET = "fcourt-facility-test";
    static final String REGION = "us-east-1";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};

    @Container
    static final MinIOContainer S3 =
            new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));

    static S3Client s3;
    static S3ImageStorage storage;

    @BeforeAll
    static void setUp() {
        s3 = S3Client.builder().endpointOverride(java.net.URI.create(S3.getS3URL())).region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3.getUserName(), S3.getPassword()))).build();
        s3.createBucket(b -> b.bucket(BUCKET));
        storage = new S3ImageStorage(s3, BUCKET, java.net.URI.create(S3.getS3URL()) + "/" + BUCKET);
    }

    @Test
    void storedImageHasTypeAndCacheHeadersAndIsGoneAfterDelete() {
        var stored = storage.store("branches/7", new ImageStorage.Upload("a.png", "image/png", PNG));

        var head = s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(stored.publicId()).build());
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.cacheControl()).isEqualTo(S3ImageStorage.CACHE_CONTROL);
        assertThat(head.contentLength()).isEqualTo(PNG.length);
        assertThat(stored.url()).isEqualTo(java.net.URI.create(S3.getS3URL()) + "/" + BUCKET + "/" + stored.publicId());

        storage.delete(stored.publicId());
        assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(stored.publicId()).build()))
                .isInstanceOf(NoSuchKeyException.class);
        storage.delete(stored.publicId()); // idempotent
    }
}
