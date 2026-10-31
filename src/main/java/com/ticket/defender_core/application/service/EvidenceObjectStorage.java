package com.ticket.defender_core.application.service;

public interface EvidenceObjectStorage {
    void checkReady();
    String put(String objectKey, ValidatedImage image);
    void delete(String objectKey, String objectVersion);

    /** Private metadata lookup. Callers must supply a server-owned key from EvidenceAsset. */
    default ObjectMetadata head(String objectKey) {
        throw new IllegalStateException("Private object metadata lookup is unavailable");
    }

    default ByteVerification verifyBytes(String objectKey, String storedMarker, long maximumBytes) {
        throw new IllegalStateException("Private object byte verification is unavailable");
    }

    /** 서버 소유 키와 저장된 객체 버전으로 원본을 제한 크기까지 읽고, 읽은 바이트와 실제 SHA-256을 함께 반환합니다. */
    default VerifiedBytes readVerifiedBytes(String objectKey, String storedMarker, long maximumBytes) {
        throw new IllegalStateException("Private object byte read is unavailable");
    }

    record ObjectMetadata(long byteSize, String mediaType, String opaqueMarker) { }
    record ByteVerification(long byteSize, String mediaType, String sha256,
                            String marker, MarkerKind markerKind) { }
    record VerifiedBytes(ByteVerification verification, byte[] bytes) { }
    enum MarkerKind { VERSION_ID, ETAG }
}
