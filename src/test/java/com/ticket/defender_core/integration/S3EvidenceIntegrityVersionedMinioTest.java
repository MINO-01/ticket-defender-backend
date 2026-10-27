package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:19000")
class S3EvidenceIntegrityVersionedMinioTest {
    @Test
    void isolatedVersionedBucketPinsHistoricalBytesButAllowsVersionDeletion() throws Exception {
        String bucket = "td-w06-versions-" + UUID.randomUUID().toString().substring(0, 12);
        String key = "fan-reports/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        try (S3Client admin = S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                        required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY")))).build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            try {
                admin.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket)
                        .versioningConfiguration(VersioningConfiguration.builder()
                                .status(BucketVersioningStatus.ENABLED).build()).build());
                assertThat(admin.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build())
                        .status()).isEqualTo(BucketVersioningStatus.ENABLED);
                byte[] original = new byte[]{1, 2, 3, 4};
                String version = admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType("image/png").build(), RequestBody.fromBytes(original)).versionId();
                assertThat(version).isNotBlank();
                admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType("image/png").build(), RequestBody.fromBytes(new byte[]{9, 8, 7}));
                S3EvidenceObjectStorage storage = new S3EvidenceObjectStorage(null, admin, bucket);
                var checked = storage.verifyBytes(key, version, 20);
                assertThat(checked.sha256()).isEqualTo(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(original)));
                assertThat(checked.markerKind().name()).isEqualTo("VERSION_ID");
                admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key)
                        .versionId(version).build());
                assertThatThrownBy(() -> storage.verifyBytes(key, version, 20))
                        .isInstanceOf(S3EvidenceObjectStorage.ObjectMarkerChangedException.class);
            } finally {
                var versions = admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).build());
                versions.versions().forEach(item -> admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                        .key(item.key()).versionId(item.versionId()).build()));
                versions.deleteMarkers().forEach(item -> admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                        .key(item.key()).versionId(item.versionId()).build()));
                admin.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
