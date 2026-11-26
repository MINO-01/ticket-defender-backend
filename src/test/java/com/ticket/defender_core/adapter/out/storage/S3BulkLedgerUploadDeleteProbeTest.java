package com.ticket.defender_core.adapter.out.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 시작 시 삭제 권한 점검 객체가 실행마다 정리되고 버킷에 쌓이지 않는지 버전 관리 버킷 대체 구현으로 확인합니다. */
class S3BulkLedgerUploadDeleteProbeTest {
    @Test
    void repeatedStartupProbesLeaveNoVersionAndReuseOneFixedKey() {
        VersionedBucket bucket = new VersionedBucket(false);
        var storage = new S3BulkLedgerUploadStorage(bucket, null, "synthetic-bucket", "track1-ledgers");

        for (int startup = 0; startup < 5; startup++) assertThat(storage.verifyDeletePermission()).isTrue();

        assertThat(bucket.entries).isEmpty();
        assertThat(bucket.keysEverWritten).containsExactly(S3BulkLedgerUploadStorage.DELETE_PROBE_KEY);
    }

    @Test
    void leftoversFromAnEarlierFailedProbeAreRemovedOnTheNextSuccessfulStartup() {
        VersionedBucket bucket = new VersionedBucket(false);
        bucket.put(S3BulkLedgerUploadStorage.DELETE_PROBE_KEY);
        bucket.put(S3BulkLedgerUploadStorage.DELETE_PROBE_KEY);
        bucket.marker(S3BulkLedgerUploadStorage.DELETE_PROBE_KEY);
        var storage = new S3BulkLedgerUploadStorage(bucket, null, "synthetic-bucket", "track1-ledgers");

        assertThat(storage.verifyDeletePermission()).isTrue();
        assertThat(bucket.entries).isEmpty();
    }

    @Test
    void withoutVersionDeletePermissionTheProbeFailsAndStillCannotMultiplyKeys() {
        VersionedBucket bucket = new VersionedBucket(true);
        var storage = new S3BulkLedgerUploadStorage(bucket, null, "synthetic-bucket", "track1-ledgers");

        for (int startup = 0; startup < 3; startup++) assertThat(storage.verifyDeletePermission()).isFalse();

        // 권한이 없으면 지울 수 없지만, 고정 키 하나만 쓰므로 점검 키가 늘어나지 않습니다.
        assertThat(bucket.keysEverWritten).containsExactly(S3BulkLedgerUploadStorage.DELETE_PROBE_KEY);
    }

    @Test
    void uploadDeletionRemovesEveryVersionAndMarkerOfTheExactKeyOnly() {
        VersionedBucket bucket = new VersionedBucket(false);
        String key = "bulk-ledger-private/" + UUID.randomUUID() + "/upload.csv";
        bucket.put(key); bucket.put(key); bucket.marker(key);
        bucket.put(key + ".other");
        var storage = new S3BulkLedgerUploadStorage(bucket, null, "synthetic-bucket", "track1-ledgers");

        storage.deleteAllVersions(key);

        assertThat(bucket.entries).extracting(Entry::key).containsExactly(key + ".other");
    }

    private record Entry(String key, String versionId, boolean marker) { }

    /** ListObjectVersions, PutObject, DeleteObject만 흉내 내는 버전 관리 버킷입니다. */
    private static final class VersionedBucket implements S3Client {
        final List<Entry> entries = new ArrayList<>();
        final Set<String> keysEverWritten = new HashSet<>();
        private final boolean denyVersionDelete;

        VersionedBucket(boolean denyVersionDelete) { this.denyVersionDelete = denyVersionDelete; }

        void put(String key) { entries.add(new Entry(key, UUID.randomUUID().toString(), false)); keysEverWritten.add(key); }
        void marker(String key) { entries.add(new Entry(key, UUID.randomUUID().toString(), true)); }

        @Override public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            put(request.key());
            return PutObjectResponse.builder().versionId(entries.getLast().versionId()).build();
        }

        @Override public ListObjectVersionsResponse listObjectVersions(ListObjectVersionsRequest request) {
            List<ObjectVersion> versions = new ArrayList<>();
            List<DeleteMarkerEntry> markers = new ArrayList<>();
            for (Entry entry : entries) {
                if (!entry.key().startsWith(request.prefix())) continue;
                if (entry.marker()) markers.add(DeleteMarkerEntry.builder().key(entry.key()).versionId(entry.versionId()).build());
                else versions.add(ObjectVersion.builder().key(entry.key()).versionId(entry.versionId()).build());
            }
            return ListObjectVersionsResponse.builder().versions(versions).deleteMarkers(markers).isTruncated(false).build();
        }

        @Override public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
            if (request.versionId() == null) { marker(request.key()); return DeleteObjectResponse.builder().build(); }
            if (denyVersionDelete) throw (S3Exception) S3Exception.builder().statusCode(403).message("synthetic AccessDenied").build();
            entries.removeIf(entry -> entry.key().equals(request.key()) && entry.versionId().equals(request.versionId()));
            return DeleteObjectResponse.builder().build();
        }

        @Override public String serviceName() { return "s3"; }
        @Override public void close() { }
    }
}
