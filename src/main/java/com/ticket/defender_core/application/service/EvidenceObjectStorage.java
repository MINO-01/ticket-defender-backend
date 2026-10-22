package com.ticket.defender_core.application.service;

public interface EvidenceObjectStorage {
    void checkReady();
    String put(String objectKey, ValidatedImage image);
    void delete(String objectKey, String objectVersion);

    /** Private metadata lookup. Callers must supply a server-owned key from EvidenceAsset. */
    default ObjectMetadata head(String objectKey) {
        throw new IllegalStateException("Private object metadata lookup is unavailable");
    }

    record ObjectMetadata(long byteSize, String mediaType, String opaqueMarker) { }
}
