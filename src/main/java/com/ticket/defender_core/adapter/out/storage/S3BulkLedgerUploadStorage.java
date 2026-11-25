package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Requires S3 versioning: an ETag is never accepted as a BL-02 version marker. */
public final class S3BulkLedgerUploadStorage implements BulkLedgerUploadStorage {
    private final S3Client reader;
    private final S3Presigner presigner;
    private final String bucket;
    private final String bulkArtifactPrefix;

    public S3BulkLedgerUploadStorage(S3Client reader, S3Presigner presigner, String bucket, String bulkArtifactPrefix) {
        this.reader = reader; this.presigner = presigner; this.bucket = bucket; this.bulkArtifactPrefix = bulkArtifactPrefix;
    }

    @Override public UploadSession createUploadSession(String key, long bytes, Instant expiresAt) {
        try {
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isNegative() || ttl.isZero()) throw new InvalidObject();
            PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request.signatureDuration(ttl)
                    .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/csv")
                            .contentLength(bytes).build()));
            return new UploadSession(signed.url().toURI(), expiresAt);
        } catch (InvalidObject failure) { throw failure;
        } catch (Exception failure) { throw new Unavailable(failure); }
    }

    @Override public VerifiedObject verify(String key, String expectedVersionId, long maximumBytes) {
        try {
            HeadObjectResponse head = reader.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            String version = version(head.versionId());
            if (version == null) throw new InvalidObject();
            if (expectedVersionId != null && !expectedVersionId.equals(version)) throw new ObjectChanged();
            if (head.contentLength() == null || head.contentLength() < 0 || head.contentLength() > maximumBytes) throw new InvalidObject();
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (ResponseInputStream<?> stream = reader.getObject(GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build())) {
                byte[] bytes = new byte[8192]; int read;
                while ((read = stream.read(bytes)) >= 0) { count += read; if (count > maximumBytes) throw new InvalidObject(); digest.update(bytes, 0, read); }
            }
            if (count != head.contentLength()) throw new ObjectChanged();
            return new VerifiedObject(version, count, HexFormat.of().formatHex(digest.digest()));
        } catch (ObjectChanged | InvalidObject failure) { throw failure;
        } catch (IOException failure) { throw new Unavailable(new UncheckedIOException(failure));
        } catch (Exception failure) { throw new Unavailable(failure); }
    }

    @Override public InputStream openVersion(String key, String versionId) {
        try { return reader.getObject(GetObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build()); }
        catch (Exception failure) { throw new Unavailable(failure); }
    }
    @Override public VerifiedObject putImmutable(String artifactName, InputStream content, long bytes, String sha256, Runnable progress) {
        try {
            String key = bulkObjectKey(bulkArtifactPrefix, artifactName);
            var response = reader.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/x-ndjson; charset=utf-8").contentLength(bytes).build(),
                    RequestBody.fromInputStream(new ProgressInputStream(content, progress), bytes));
            String version = version(response.versionId());
            if (version == null) throw new InvalidObject();
            VerifiedObject verified = verify(key, version, bytes);
            if (!sha256.equals(verified.sha256())) throw new ObjectChanged();
            return verified;
        } catch (ObjectChanged | InvalidObject failure) { throw failure;
        } catch (RuntimeException failure) { throw failure;
        } catch (Exception failure) { throw new Unavailable(failure); }
    }
    @Override public void deleteAllVersions(String key) {
        if (key == null || key.isBlank()) throw new InvalidObject();
        try {
            String keyMarker = null, versionMarker = null;
            while (true) {
                var request = software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest.builder().bucket(bucket).prefix(key);
                if (keyMarker != null) request.keyMarker(keyMarker);
                if (versionMarker != null) request.versionIdMarker(versionMarker);
                var page = reader.listObjectVersions(request.build());
                // 접두 일치가 아니라 정확히 같은 키의 버전과 delete marker만 지웁니다.
                for (var version : page.versions()) if (key.equals(version.key())) deleteVersion(key, version.versionId());
                for (var marker : page.deleteMarkers()) if (key.equals(marker.key())) deleteVersion(key, marker.versionId());
                if (!Boolean.TRUE.equals(page.isTruncated())) break;
                keyMarker = page.nextKeyMarker(); versionMarker = page.nextVersionIdMarker();
            }
        } catch (InvalidObject failure) { throw failure;
        } catch (Exception failure) { throw new Unavailable("Upload object deletion failed"); }
    }

    @Override public boolean verifyDeletePermission() {
        String key = "bulk-ledger-private/_delete-permission-probe/" + java.util.UUID.randomUUID() + ".txt";
        try {
            byte[] probe = "td-delete-probe".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            reader.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain").contentLength((long) probe.length).build(),
                    RequestBody.fromBytes(probe));
            deleteAllVersions(key);
            try {
                reader.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                return false;
            } catch (software.amazon.awssdk.services.s3.model.S3Exception missing) {
                return missing.statusCode() == 404;
            }
        } catch (Exception unverified) {
            return false;
        }
    }

    private void deleteVersion(String key, String versionId) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) throw new InvalidObject();
        reader.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build());
    }

    /** Data adds its configured prefix to this opaque manifest name; backend must upload to the identical S3 key. */
    static String bulkObjectKey(String prefix, String artifactName) {
        if (prefix == null || prefix.isBlank() || artifactName == null || !artifactName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new InvalidObject();
        return prefix.replaceAll("/+$", "") + "/" + artifactName;
    }
    private static final class ProgressInputStream extends FilterInputStream {
        private final Runnable progress; private long sinceProgress;
        private ProgressInputStream(InputStream input, Runnable progress) { super(input); this.progress=progress; progress.run(); }
        @Override public int read() throws IOException { int result=super.read(); if(result>=0) progressed(1); return result; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { int result=super.read(bytes,offset,length); if(result>0) progressed(result); return result; }
        private void progressed(int count) { sinceProgress+=count; if(sinceProgress>=1_048_576) { progress.run(); sinceProgress=0; } }
    }
    private static String version(String value) { return value == null || value.isBlank() || "null".equals(value) ? null : value; }
}
