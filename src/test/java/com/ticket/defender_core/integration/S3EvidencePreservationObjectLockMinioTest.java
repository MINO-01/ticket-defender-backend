package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.storage.S3EvidencePreservationStorage;
import com.ticket.defender_core.application.service.EvidencePreservationStorage;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE40_MINIO_BUCKET",
        matches = "td-issue40-ci-preservation")
class S3EvidencePreservationObjectLockMinioTest {
    @Test
    void governanceLockPinsVersionAcrossDeleteMarkerAndRequiresBypassForVersionDeletion() throws Exception {
        String bucket = required("TD_ISSUE40_MINIO_BUCKET");
        String key = "preserved-evidence/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        String unlockedKey = "preserved-evidence/unlocked/" + UUID.randomUUID();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        String digest = sha256(bytes);
        Instant retainUntil = Instant.now().plusSeconds(180);
        try (S3Client admin = admin();
             S3Client verifier = client(required("TD_ISSUE40_MINIO_VERIFIER_ACCESS_KEY"),
                     required("TD_ISSUE40_MINIO_VERIFIER_SECRET_KEY"));
             S3Client reader = client(required("TD_ISSUE40_MINIO_READER_ACCESS_KEY"),
                     required("TD_ISSUE40_MINIO_READER_SECRET_KEY"));
             S3Client deleter = client(required("TD_ISSUE40_MINIO_DELETE_ACCESS_KEY"),
                     required("TD_ISSUE40_MINIO_DELETE_SECRET_KEY"))) {
            assertThat(admin.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build())
                    .status()).isEqualTo(BucketVersioningStatus.ENABLED);
            assertThat(admin.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder()
                    .bucket(bucket).build()).objectLockConfiguration().objectLockEnabled())
                    .isEqualTo(ObjectLockEnabled.ENABLED);
            String versionId = admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentType("image/png").objectLockMode(ObjectLockMode.GOVERNANCE)
                            .objectLockRetainUntilDate(retainUntil).build(), RequestBody.fromBytes(bytes))
                    .versionId();
            assertThat(versionId).isNotBlank().isNotEqualTo("null");

            S3EvidencePreservationStorage storage = new S3EvidencePreservationStorage(verifier, bucket,
                    required("TD_ISSUE40_MINIO_PROFILE"));
            assertProtectedVersion(storage, reader, bucket, key, versionId, digest, bytes, retainUntil);
            assertThatThrownBy(() -> reader.getObjectRetention(GetObjectRetentionRequest.builder()
                    .bucket(bucket).key(key).versionId(versionId).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            error -> assertS3Error(error, 403, "AccessDenied"));
            assertThatThrownBy(() -> deleter.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                    .key(key).versionId(versionId).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            error -> assertS3Error(error, 400, "InvalidRequest"));
            assertProtectedVersion(storage, reader, bucket, key, versionId, digest, bytes, retainUntil);
            assertThatThrownBy(() -> deleter.deleteObject(DeleteObjectRequest.builder().bucket(bucket)
                    .key(key).versionId(versionId).bypassGovernanceRetention(true).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            error -> assertS3Error(error, 403, "AccessDenied"));
            assertProtectedVersion(storage, reader, bucket, key, versionId, digest, bytes, retainUntil);

            DeleteObjectResponse marker = deleter.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            assertThat(marker.deleteMarker()).isTrue();
            assertThat(marker.versionId()).isNotBlank();
            ListObjectVersionsResponse versions = admin.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket).prefix(key).build());
            assertThat(versions.versions()).anySatisfy(version -> {
                assertThat(version.key()).isEqualTo(key);
                assertThat(version.versionId()).isEqualTo(versionId);
            });
            assertThat(versions.deleteMarkers()).anySatisfy(deleteMarker -> {
                assertThat(deleteMarker.key()).isEqualTo(key);
                assertThat(deleteMarker.versionId()).isEqualTo(marker.versionId());
            });
            assertProtectedVersion(storage, reader, bucket, key, versionId, digest, bytes, retainUntil);

            String unlockedVersion = admin.putObject(PutObjectRequest.builder().bucket(bucket)
                    .key(unlockedKey).build(), RequestBody.fromBytes(bytes)).versionId();
            assertThat(unlockedVersion).isNotBlank();
            assertThat(reader.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                    .key(unlockedKey).versionId(unlockedVersion).build()).asByteArray()).isEqualTo(bytes);
            assertThatThrownBy(() -> admin.getObjectRetention(GetObjectRetentionRequest.builder()
                    .bucket(bucket).key(unlockedKey).versionId(unlockedVersion).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            error -> assertS3Error(error, 400, "NoSuchObjectLockConfiguration"));
            assertThatThrownBy(() -> storage.verifyLocked(
                    unlockedKey, unlockedVersion, digest, bytes.length, 1024))
                    .isExactlyInstanceOf(
                            EvidencePreservationStorage.ObjectLockNotVerifiedException.class);
            deleter.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(unlockedKey)
                    .versionId(unlockedVersion).build());
            assertThat(admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket)
                    .prefix(unlockedKey).build()).versions()).isEmpty();

            admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key)
                    .versionId(marker.versionId()).build());
            admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key)
                    .versionId(versionId).bypassGovernanceRetention(true).build());
            assertThat(admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket)
                    .prefix(key).build()).versions()).isEmpty();
        }
    }

    @Test
    void versioningWithoutObjectLockIsRejectedAsUnsupported() {
        String bucket = "td-w07-no-lock-" + UUID.randomUUID().toString().substring(0, 12);
        String key = "preserved-evidence/" + UUID.randomUUID();
        byte[] bytes = new byte[]{5, 6, 7, 8};
        try (S3Client admin = admin()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            try {
                admin.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket)
                        .versioningConfiguration(VersioningConfiguration.builder()
                                .status(BucketVersioningStatus.ENABLED).build()).build());
                String version = admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromBytes(bytes)).versionId();
                S3EvidencePreservationStorage storage = new S3EvidencePreservationStorage(
                        admin, bucket, "W07_UNSUPPORTED_TEST");
                assertThatThrownBy(() -> storage.verifyLocked(
                        key, version, sha256(bytes), bytes.length, 1024))
                        .isInstanceOf(EvidencePreservationStorage.ObjectLockUnsupportedException.class);
            } finally {
                ListObjectVersionsResponse versions = admin.listObjectVersions(
                        ListObjectVersionsRequest.builder().bucket(bucket).build());
                versions.versions().forEach(version -> admin.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(version.key()).versionId(version.versionId()).build()));
                versions.deleteMarkers().forEach(marker -> admin.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(marker.key()).versionId(marker.versionId()).build()));
                admin.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            }
        }
    }

    private static S3Client admin() {
        return client(required("TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY"),
                required("TD_ISSUE32_MINIO_ADMIN_SECRET_KEY"));
    }

    private static S3Client client(String accessKey, String secretKey) {
        return S3Client.builder().endpointOverride(URI.create(required("TD_ISSUE32_MINIO_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build();
    }

    private static void assertProtectedVersion(S3EvidencePreservationStorage storage, S3Client reader,
            String bucket, String key, String versionId, String digest, byte[] bytes, Instant retainUntil) {
        EvidencePreservationStorage.LockedObject locked = storage.verifyLocked(
                key, versionId, digest, bytes.length, 1024);
        assertThat(locked.versionId()).isEqualTo(versionId);
        assertThat(locked.sha256()).isEqualTo(digest);
        assertThat(locked.byteSize()).isEqualTo(bytes.length);
        assertThat(locked.lockMode()).isEqualTo(EvidencePreservationPolicy.Mode.GOVERNANCE);
        assertThat(locked.retainUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(locked.retainUntil()).isBeforeOrEqualTo(
                LocalDateTime.ofInstant(retainUntil.plusSeconds(1), ZoneOffset.UTC));
        assertThat(reader.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                .key(key).versionId(versionId).build()).asByteArray()).isEqualTo(bytes);
    }

    private static void assertS3Error(S3Exception error, int status, String code) {
        assertThat(error.statusCode()).isEqualTo(status);
        assertThat(error.awsErrorDetails()).isNotNull();
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo(code);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
