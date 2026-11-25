package com.ticket.defender_core.application.service;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

/** Private BL-02 object boundary. Object keys and presigned URLs never leave this boundary after upload. */
public interface BulkLedgerUploadStorage {
    UploadSession createUploadSession(String serverObjectKey, long declaredBytes, Instant expiresAt);
    VerifiedObject verify(String serverObjectKey, String expectedVersionId, long maximumBytes);
    InputStream openVersion(String serverObjectKey, String versionId);
    /** Stores an immutable, server-generated private artifact and returns the real S3 VersionId. */
    VerifiedObject putImmutable(String artifactName, InputStream content, long byteSize, String sha256, Runnable progress);

    /**
     * 업로드 원본 객체의 모든 버전과 delete marker를 지웁니다. 버전 관리 버킷에서 delete marker만 남기면 원본 바이트가
     * 남으므로 버전 ID를 지정해 삭제해야 합니다. 객체가 이미 없으면 성공으로 봅니다.
     */
    default void deleteAllVersions(String serverObjectKey) { throw new Unavailable("Upload object deletion is not configured"); }

    /**
     * 서버 전용 점검 객체를 만들고 그 버전을 실제로 지울 수 있는지 확인합니다. v3 원본 수신을 열기 전에만 호출합니다.
     * 확인하지 못하면 false를 돌려주며 예외 메시지에 자격 증명이나 버킷 정보를 담지 않습니다.
     */
    default boolean verifyDeletePermission() { return false; }

    record UploadSession(URI uploadUrl, Instant expiresAt) { }
    record VerifiedObject(String versionId, long byteSize, String sha256) { }

    class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(Throwable cause) { super(cause); }
    }
    class ObjectChanged extends RuntimeException { }
    class InvalidObject extends RuntimeException { }
}
