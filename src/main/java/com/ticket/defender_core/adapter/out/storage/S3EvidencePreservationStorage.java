package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.EvidencePreservationStorage;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

public class S3EvidencePreservationStorage implements EvidencePreservationStorage {
    private final S3Client client;
    private final String bucket;
    private final String profile;

    public S3EvidencePreservationStorage(S3Client client, String bucket, String profile) {
        this.client = Objects.requireNonNull(client);
        this.bucket = Objects.requireNonNull(bucket);
        this.profile = Objects.requireNonNull(profile);
    }

    @Override
    public String profile() {
        return profile;
    }

    @Override
    public LockedObject verifyLocked(String key, String requestedVersion, String expectedSha256,
            long expectedSize, long maximumBytes) {
        if (key == null || key.isBlank() || expectedSha256 == null || expectedSha256.length() != 64
                || expectedSize < 1 || maximumBytes < expectedSize)
            throw new IllegalArgumentException("Preservation verification input is invalid");
        verifyBucketCapabilities();
        try {
            String requested = usableVersion(requestedVersion);
            HeadObjectRequest.Builder headRequest = HeadObjectRequest.builder().bucket(bucket).key(key);
            if (requested != null) headRequest.versionId(requested);
            HeadObjectResponse head = client.headObject(headRequest.build());
            String versionId = usableVersion(head.versionId());
            if (versionId == null || (requested != null && !requested.equals(versionId))
                    || Boolean.TRUE.equals(head.deleteMarker()))
                throw new ObjectLockNotVerifiedException();
            ObjectLockRetention retention;
            try {
                retention = client.getObjectRetention(GetObjectRetentionRequest.builder()
                        .bucket(bucket).key(key).versionId(versionId).build()).retention();
            } catch (S3Exception error) {
                if (retentionMissing(error)) throw new ObjectLockNotVerifiedException();
                throw error;
            }
            if (retention == null || retention.mode() == null || retention.retainUntilDate() == null)
                throw new ObjectLockNotVerifiedException();
            if (head.contentLength() == null || head.contentLength() != expectedSize
                    || head.contentLength() > maximumBytes) throw new ObjectMismatchException();

            String digest = digest(key, versionId, expectedSize, maximumBytes);
            if (!sameDigest(digest, expectedSha256)) throw new ObjectMismatchException();
            EvidencePreservationPolicy.Mode mode = retention.mode() == ObjectLockRetentionMode.COMPLIANCE
                    ? EvidencePreservationPolicy.Mode.COMPLIANCE
                    : retention.mode() == ObjectLockRetentionMode.GOVERNANCE
                    ? EvidencePreservationPolicy.Mode.GOVERNANCE : null;
            if (mode == null) throw new ObjectLockNotVerifiedException();
            return new LockedObject(versionId, digest, expectedSize, mode,
                    LocalDateTime.ofInstant(retention.retainUntilDate(), ZoneOffset.UTC));
        } catch (ObjectLockNotVerifiedException | ObjectMismatchException known) {
            throw known;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) throw new ObjectMismatchException();
            throw new StorageUnavailableException(error);
        } catch (RuntimeException failure) {
            throw new StorageUnavailableException(failure);
        }
    }

    private void verifyBucketCapabilities() {
        try {
            if (client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status()
                    != BucketVersioningStatus.ENABLED) throw new ObjectLockUnsupportedException();
            var configuration = client.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder()
                    .bucket(bucket).build()).objectLockConfiguration();
            if (configuration == null || configuration.objectLockEnabled() != ObjectLockEnabled.ENABLED)
                throw new ObjectLockUnsupportedException();
        } catch (ObjectLockUnsupportedException unsupported) {
            throw unsupported;
        } catch (S3Exception error) {
            if (error.statusCode() == 400 || error.statusCode() == 404)
                throw new ObjectLockUnsupportedException();
            throw new StorageUnavailableException(error);
        } catch (RuntimeException failure) {
            throw new StorageUnavailableException(failure);
        }
    }

    private String digest(String key, String versionId, long expectedSize, long maximumBytes) {
        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).versionId(versionId).build())) {
            if (!versionId.equals(usableVersion(stream.response().versionId())))
                throw new ObjectMismatchException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new ObjectMismatchException();
                digest.update(buffer, 0, count);
            }
            if (total != expectedSize || stream.response().contentLength() == null
                    || total != stream.response().contentLength()) throw new ObjectMismatchException();
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new UncheckedIOException("Preserved object read failed", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean sameDigest(String actual, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(actual), HexFormat.of().parseHex(expected));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean retentionMissing(S3Exception error) {
        if (error.statusCode() == 404) return true;
        return error.statusCode() == 400 && error.awsErrorDetails() != null
                && "NoSuchObjectLockConfiguration".equals(error.awsErrorDetails().errorCode());
    }

    private static String usableVersion(String versionId) {
        return versionId == null || versionId.isBlank() || "null".equals(versionId) ? null : versionId;
    }
}
