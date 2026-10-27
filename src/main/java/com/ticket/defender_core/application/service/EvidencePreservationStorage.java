package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.EvidencePreservationPolicy;

import java.time.LocalDateTime;

public interface EvidencePreservationStorage {
    String profile();

    LockedObject verifyLocked(String objectKey, String versionId, String expectedSha256,
            long expectedSize, long maximumBytes);

    record LockedObject(String versionId, String sha256, long byteSize,
                        EvidencePreservationPolicy.Mode lockMode, LocalDateTime retainUntil) { }

    class ObjectLockUnsupportedException extends RuntimeException { }
    class ObjectLockNotVerifiedException extends RuntimeException { }
    class ObjectMismatchException extends RuntimeException { }
    class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(Throwable cause) { super(cause); }
        public StorageUnavailableException(String message) { super(message); }
    }
}
