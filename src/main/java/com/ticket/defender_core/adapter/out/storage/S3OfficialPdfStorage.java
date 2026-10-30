package com.ticket.defender_core.adapter.out.storage;

import com.ticket.defender_core.application.service.OfficialPdfStorage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** S3 adapter that only accepts immutable object versions and never offers presigned URLs. */
public class S3OfficialPdfStorage implements OfficialPdfStorage {
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final S3Client client;
    private final String bucket;
    private final long maximumBytes;

    public S3OfficialPdfStorage(S3Client client, String bucket, long maximumBytes) {
        this.client = Objects.requireNonNull(client);
        this.bucket = Objects.requireNonNull(bucket);
        if (maximumBytes < 1) throw new IllegalArgumentException("Official PDF maximum size must be positive");
        this.maximumBytes = maximumBytes;
    }

    @Override
    public void checkReady() {
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }

    @Override
    public ObjectProof putIfAbsent(UUID issuanceId, String objectKey, byte[] pdfBytes) {
        validateInput(issuanceId, objectKey, pdfBytes);
        String digest = sha256(pdfBytes);
        try {
            PutObjectResponse response = client.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(objectKey).contentType(PDF_MEDIA_TYPE).ifNoneMatch("*")
                            .metadata(Map.of("issuance-id", issuanceId.toString())).build(),
                    RequestBody.fromBytes(pdfBytes));
            String version = requiredVersion(response.versionId());
            return verify(issuanceId, objectKey, version, digest, pdfBytes.length);
        } catch (S3Exception collision) {
            if (collision.statusCode() != 412 && collision.statusCode() != 409) throw collision;
            try {
                HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket).key(objectKey).build());
                verifyOwnership(head, issuanceId);
                if (head.contentLength() == null || head.contentLength() < 1 || head.contentLength() > maximumBytes
                        || !PDF_MEDIA_TYPE.equalsIgnoreCase(head.contentType())) throw new ObjectVerificationException();
                // A deterministic issuance key is only reusable when it contains the exact bytes rendered
                // for this request. Ownership and a PDF content type alone are not a recovery proof.
                return read(objectKey, requiredVersion(head.versionId()), digest, pdfBytes.length, false).proof();
            } catch (S3Exception unavailable) {
                throw unavailable;
            }
        }
    }

    @Override
    public ObjectProof verify(UUID issuanceId, String objectKey, String objectVersion, String expectedSha256, long expectedSize) {
        if (issuanceId == null || objectKey == null || objectKey.isBlank() || expectedSha256 == null || expectedSha256.length() != 64
                || expectedSize < 1 || expectedSize > maximumBytes) throw new IllegalArgumentException("PDF proof is invalid");
        String version = requiredVersion(objectVersion);
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey)
                .versionId(version).build());
        verifyOwnership(head, issuanceId);
        if (head.contentLength() == null || head.contentLength() != expectedSize
                || !PDF_MEDIA_TYPE.equalsIgnoreCase(head.contentType())
                || !version.equals(requiredVersion(head.versionId()))) throw new ObjectVerificationException();
        return read(objectKey, version, expectedSha256, expectedSize, false).proof();
    }

    @Override
    public DownloadedPdf download(UUID issuanceId, String objectKey, String objectVersion, String expectedSha256, long expectedSize) {
        if (issuanceId == null || objectKey == null || objectKey.isBlank() || expectedSha256 == null || expectedSha256.length() != 64
                || expectedSize < 1 || expectedSize > maximumBytes) throw new IllegalArgumentException("PDF proof is invalid");
        String version = requiredVersion(objectVersion);
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey)
                .versionId(version).build());
        verifyOwnership(head, issuanceId);
        if (head.contentLength() == null || head.contentLength() != expectedSize
                || !PDF_MEDIA_TYPE.equalsIgnoreCase(head.contentType())
                || !version.equals(requiredVersion(head.versionId()))) throw new ObjectVerificationException();
        Read result = read(objectKey, version, expectedSha256, expectedSize, true);
        return new DownloadedPdf(result.bytes(), result.proof().sha256(), result.proof().size());
    }

    private Read read(String key, String version, String expectedSha256, long expectedSize, boolean retainBytes) {
        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).versionId(version).build())) {
            GetObjectResponse response = stream.response();
            if (!version.equals(requiredVersion(response.versionId())) || response.contentLength() == null
                    || response.contentLength() != expectedSize
                    || !PDF_MEDIA_TYPE.equalsIgnoreCase(response.contentType())) throw new ObjectVerificationException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream output = retainBytes ? new ByteArrayOutputStream(Math.toIntExact(expectedSize)) : null;
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new ObjectVerificationException();
                digest.update(buffer, 0, count);
                if (output != null) output.write(buffer, 0, count);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (total != expectedSize || (expectedSha256 != null && !sameDigest(actual, expectedSha256)))
                throw new ObjectVerificationException();
            return new Read(new ObjectProof(version, actual, total), output == null ? null : output.toByteArray());
        } catch (IOException error) {
            throw new UncheckedIOException("Official PDF object read failed", error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void validateInput(UUID issuanceId, String objectKey, byte[] bytes) {
        if (issuanceId == null || objectKey == null || objectKey.isBlank() || bytes == null
                || bytes.length < 1 || bytes.length > maximumBytes)
            throw new IllegalArgumentException("Official PDF input is invalid");
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw new IllegalArgumentException("Official PDF bytes are invalid");
    }

    private static void verifyOwnership(HeadObjectResponse head, UUID issuanceId) {
        if (head.metadata() == null || !issuanceId.toString().equals(head.metadata().get("issuance-id")))
            throw new ObjectVerificationException();
    }

    private static String requiredVersion(String version) {
        if (version == null || version.isBlank() || "null".equals(version))
            throw new ObjectVerificationException();
        return version;
    }

    private static boolean sameDigest(String actual, String expected) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(actual), HexFormat.of().parseHex(expected));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Read(ObjectProof proof, byte[] bytes) { }
    public static class ObjectVerificationException extends RuntimeException { }
}
