package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.BulkLedgerUploadStorage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
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

    public S3BulkLedgerUploadStorage(S3Client reader, S3Presigner presigner, String bucket) {
        this.reader = reader; this.presigner = presigner; this.bucket = bucket;
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
    private static String version(String value) { return value == null || value.isBlank() || "null".equals(value) ? null : value; }
}
