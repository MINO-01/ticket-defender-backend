package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ValidatedImage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class S3EvidenceObjectStorage implements EvidenceObjectStorage {
    private final S3Client writeClient;
    private final S3Client headClient;
    private final String bucket;

    public S3EvidenceObjectStorage(S3Client client, String bucket) {
        this(client, client, bucket);
    }

    public S3EvidenceObjectStorage(S3Client writeClient, S3Client headClient, String bucket) {
        this.writeClient = writeClient;
        this.headClient = headClient;
        this.bucket = bucket;
    }

    @Override
    public void checkReady() {
        S3Client client = writeClient != null ? writeClient : headClient;
        if (client == null) throw new IllegalStateException("Private storage credentials are not configured");
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }

    @Override
    public String put(String key, ValidatedImage image) {
        if (writeClient == null) throw new IllegalStateException("Private storage write is disabled");
        PutObjectResponse response = writeClient.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key).contentType(image.mediaType())
                        .ifNoneMatch("*").build(),
                RequestBody.fromBytes(image.bytes()));
        return response.versionId() == null ? response.eTag() : response.versionId();
    }

    @Override
    public void delete(String key, String version) {
        // The dedicated intake bucket is required to have versioning disabled.
        if (writeClient == null) throw new IllegalStateException("Private storage write is disabled");
        writeClient.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public ObjectMetadata head(String key) {
        if (headClient == null) throw new IllegalStateException("Private storage metadata credentials are not configured");
        HeadObjectResponse response = headClient.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(key).build());
        String marker = response.versionId() == null ? response.eTag() : response.versionId();
        return new ObjectMetadata(response.contentLength(), response.contentType(), marker);
    }

    @Override
    public ByteVerification verifyBytes(String key, String storedMarker, long maximumBytes) {
        return read(key, storedMarker, maximumBytes, null);
    }

    @Override
    public VerifiedBytes readVerifiedBytes(String key, String storedMarker, long maximumBytes) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ByteVerification verification = read(key, storedMarker, maximumBytes, sink);
        return new VerifiedBytes(verification, sink.toByteArray());
    }

    private ByteVerification read(String key, String storedMarker, long maximumBytes, ByteArrayOutputStream sink) {
        if (headClient == null) throw new IllegalStateException("Private storage read credentials are not configured");
        if (maximumBytes < 1) throw new IllegalArgumentException("Maximum byte count must be positive");
        HeadObjectResponse head = headClient.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(key).build());
        if (storedMarker == null || storedMarker.isBlank()) throw new ObjectMarkerChangedException();
        String versionId = usableVersion(head.versionId());
        if (versionId != null && !versionId.equals(storedMarker)) {
            try {
                head = headClient.headObject(HeadObjectRequest.builder()
                        .bucket(bucket).key(key).versionId(storedMarker).build());
                versionId = usableVersion(head.versionId());
            } catch (S3Exception missingVersion) {
                if (missingVersion.statusCode() == 404) throw new ObjectMarkerChangedException();
                throw missingVersion;
            }
        }
        String marker = versionId != null ? versionId : head.eTag();
        if (marker == null || !Objects.equals(marker, storedMarker))
            throw new ObjectMarkerChangedException();
        if (head.contentLength() == null || head.contentLength() < 0 || head.contentLength() > maximumBytes)
            throw new ObjectSizeExceededException();
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (versionId != null) request.versionId(versionId);
        else request.ifMatch(head.eTag());
        try (ResponseInputStream<GetObjectResponse> stream = headClient.getObject(request.build())) {
            GetObjectResponse response = stream.response();
            String observedVersion = usableVersion(response.versionId());
            String observedMarker = observedVersion != null ? observedVersion : response.eTag();
            if (!marker.equals(observedMarker) || !Objects.equals(head.contentType(), response.contentType()))
                throw new ObjectMarkerChangedException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new ObjectSizeExceededException();
                digest.update(buffer, 0, count);
                if (sink != null) sink.write(buffer, 0, count);
            }
            if (response.contentLength() == null || total != response.contentLength()
                    || total != head.contentLength()) throw new ObjectMarkerChangedException();
            return new ByteVerification(total, response.contentType(),
                    HexFormat.of().formatHex(digest.digest()), observedMarker,
                    observedVersion != null ? MarkerKind.VERSION_ID : MarkerKind.ETAG);
        } catch (IOException error) {
            throw new UncheckedIOException("Private object read failed", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String usableVersion(String versionId) {
        return versionId == null || versionId.isBlank() || "null".equals(versionId) ? null : versionId;
    }

    public static class ObjectMarkerChangedException extends RuntimeException { }
    public static class ObjectSizeExceededException extends RuntimeException { }
}
