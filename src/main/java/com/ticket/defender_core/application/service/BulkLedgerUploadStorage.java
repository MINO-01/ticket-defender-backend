package com.ticket.defender_core.application.service;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

/** Private BL-02 object boundary. Object keys and presigned URLs never leave this boundary after upload. */
public interface BulkLedgerUploadStorage {
    UploadSession createUploadSession(String serverObjectKey, long declaredBytes, Instant expiresAt);
    VerifiedObject verify(String serverObjectKey, String expectedVersionId, long maximumBytes);
    InputStream openVersion(String serverObjectKey, String versionId);

    record UploadSession(URI uploadUrl, Instant expiresAt) { }
    record VerifiedObject(String versionId, long byteSize, String sha256) { }

    class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(Throwable cause) { super(cause); }
    }
    class ObjectChanged extends RuntimeException { }
    class InvalidObject extends RuntimeException { }
}
