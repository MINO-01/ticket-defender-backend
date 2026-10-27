package com.ticket.defender_core.adapter.out.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3EvidenceObjectStorageByteTest {
    private static final String KEY = "fan-reports/server-report/server-evidence";

    @Test
    void unversionedGetUsesEtagConditionAndStreamsExactBytesWithReadClient() throws Exception {
        S3Client writer = mock(S3Client.class);
        S3Client reader = mock(S3Client.class);
        byte[] bytes = new byte[]{1, 2, 3, 4};
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(head(4, "etag", null));
        when(reader.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                response(4, "etag", null), new ByteArrayInputStream(bytes)));

        var result = new S3EvidenceObjectStorage(writer, reader, "private")
                .verifyBytes(KEY, "etag", 20);

        assertThat(result.byteSize()).isEqualTo(4);
        assertThat(result.sha256()).isEqualTo(sha256(bytes));
        assertThat(result.markerKind().name()).isEqualTo("ETAG");
        verify(reader).getObject(GetObjectRequest.builder().bucket("private").key(KEY).ifMatch("etag").build());
        verifyNoInteractions(writer);
    }

    @Test
    void versionedGetPinsRealVersionRatherThanEtag() {
        S3Client reader = mock(S3Client.class);
        byte[] bytes = new byte[]{8, 7, 6};
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(head(3, "etag", "version-1"));
        when(reader.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                response(3, "etag", "version-1"), new ByteArrayInputStream(bytes)));

        var result = new S3EvidenceObjectStorage(null, reader, "private")
                .verifyBytes(KEY, "version-1", 20);

        assertThat(result.markerKind().name()).isEqualTo("VERSION_ID");
        verify(reader).getObject(GetObjectRequest.builder().bucket("private").key(KEY)
                .versionId("version-1").build());
    }

    @Test
    void historicalVersionIsReadByStoredVersionIdEvenAfterLatestChanges() throws Exception {
        S3Client reader = mock(S3Client.class);
        byte[] bytes = new byte[]{8, 7, 6};
        when(reader.headObject(HeadObjectRequest.builder().bucket("private").key(KEY).build()))
                .thenReturn(head(3, "new-etag", "version-2"));
        when(reader.headObject(HeadObjectRequest.builder().bucket("private").key(KEY)
                .versionId("version-1").build())).thenReturn(head(3, "old-etag", "version-1"));
        when(reader.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                response(3, "old-etag", "version-1"), new ByteArrayInputStream(bytes)));

        var result = new S3EvidenceObjectStorage(null, reader, "private")
                .verifyBytes(KEY, "version-1", 20);

        assertThat(result.sha256()).isEqualTo(sha256(bytes));
        assertThat(result.markerKind().name()).isEqualTo("VERSION_ID");
        verify(reader).getObject(GetObjectRequest.builder().bucket("private").key(KEY)
                .versionId("version-1").build());
    }

    @Test
    void changedMarkerAndOversizeRejectBeforeGet() {
        S3Client reader = mock(S3Client.class);
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(head(4, "other", null));
        var storage = new S3EvidenceObjectStorage(null, reader, "private");
        assertThatThrownBy(() -> storage.verifyBytes(KEY, "etag", 4))
                .isInstanceOf(S3EvidenceObjectStorage.ObjectMarkerChangedException.class);
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(head(5, "etag", null));
        assertThatThrownBy(() -> storage.verifyBytes(KEY, "etag", 4))
                .isInstanceOf(S3EvidenceObjectStorage.ObjectSizeExceededException.class);
        verify(reader, org.mockito.Mockito.never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void streamingLimitAndIOExceptionAlwaysCloseObjectStream() {
        S3Client reader = mock(S3Client.class);
        when(reader.headObject(any(HeadObjectRequest.class))).thenReturn(head(3, "etag", null));
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream oversized = new ByteArrayInputStream(new byte[]{1, 2, 3, 4}) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        when(reader.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                response(3, "etag", null), oversized));
        var storage = new S3EvidenceObjectStorage(null, reader, "private");
        assertThatThrownBy(() -> storage.verifyBytes(KEY, "etag", 3))
                .isInstanceOf(S3EvidenceObjectStorage.ObjectSizeExceededException.class);
        assertThat(closed).isTrue();

        AtomicBoolean failedClosed = new AtomicBoolean();
        var failing = new ByteArrayInputStream(new byte[]{1}) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                throw new UncheckedIOException(new IOException("synthetic failure"));
            }
            @Override public void close() throws IOException { failedClosed.set(true); super.close(); }
        };
        when(reader.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                response(3, "etag", null), failing));
        assertThatThrownBy(() -> storage.verifyBytes(KEY, "etag", 3))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(failedClosed).isTrue();
    }

    private static HeadObjectResponse head(long size, String etag, String version) {
        return HeadObjectResponse.builder().contentLength(size).contentType("image/png")
                .eTag(etag).versionId(version).build();
    }
    private static GetObjectResponse response(long size, String etag, String version) {
        return GetObjectResponse.builder().contentLength(size).contentType("image/png")
                .eTag(etag).versionId(version).build();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
