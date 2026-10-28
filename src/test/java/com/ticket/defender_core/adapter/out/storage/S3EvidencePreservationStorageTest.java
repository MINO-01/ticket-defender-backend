package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.EvidencePreservationStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3EvidencePreservationStorageTest {
    private static final String BUCKET = "preservation";
    private static final String KEY = "preserved-evidence/tenant/event/case/evidence";
    private static final String VERSION = "version-1";
    private static final String SHA256 = "a".repeat(64);
    private static final long SIZE = 4L;

    private S3Client client;
    private S3EvidencePreservationStorage storage;

    @BeforeEach
    void setup() {
        client = mock(S3Client.class);
        storage = new S3EvidencePreservationStorage(client, BUCKET, "W07_TEST_LOCKED");
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
                .thenReturn(GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.ENABLED).build());
        when(client.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class)))
                .thenReturn(GetObjectLockConfigurationResponse.builder()
                        .objectLockConfiguration(ObjectLockConfiguration.builder()
                                .objectLockEnabled(ObjectLockEnabled.ENABLED).build())
                        .build());
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .versionId(VERSION).contentLength(SIZE).build());
    }

    @Test
    void retentionNotFoundIsNotReportedAsObjectMismatch() {
        when(client.getObjectRetention(any(GetObjectRetentionRequest.class)))
                .thenThrow(s3(404, "NoSuchObjectLockConfiguration"));

        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.ObjectLockNotVerifiedException.class);
    }

    @Test
    void minioMissingRetentionErrorIsObjectLockNotVerified() {
        when(client.getObjectRetention(any(GetObjectRetentionRequest.class)))
                .thenThrow(s3(400, "NoSuchObjectLockConfiguration"));

        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.ObjectLockNotVerifiedException.class);
    }

    @Test
    void unknownRetentionBadRequestAndAccessDeniedRemainUnavailable() {
        when(client.getObjectRetention(any(GetObjectRetentionRequest.class)))
                .thenThrow(s3(400, "InvalidRequest"));
        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.StorageUnavailableException.class);

        when(client.getObjectRetention(any(GetObjectRetentionRequest.class)))
                .thenThrow(s3(403, "AccessDenied"));
        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.StorageUnavailableException.class);
    }

    @Test
    void missingObjectAndVersionRemainObjectMismatch() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(s3(404, "NoSuchKey"));
        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.ObjectMismatchException.class);

        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .versionId(VERSION).contentLength(SIZE).build());
        when(client.getObjectRetention(any(GetObjectRetentionRequest.class))).thenReturn(
                GetObjectRetentionResponse.builder().retention(ObjectLockRetention.builder()
                        .mode(ObjectLockRetentionMode.GOVERNANCE)
                        .retainUntilDate(Instant.now().plusSeconds(60)).build()).build());
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(s3(404, "NoSuchVersion"));
        assertThatThrownBy(() -> verify(null))
                .isExactlyInstanceOf(EvidencePreservationStorage.ObjectMismatchException.class);
    }

    private EvidencePreservationStorage.LockedObject verify(String requestedVersion) {
        return storage.verifyLocked(KEY, requestedVersion, SHA256, SIZE, 1024);
    }

    private static S3Exception s3(int status, String code) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }
}
