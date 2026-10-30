package com.ticket.defender_core.application.service;

/** Private server-side storage for official PDFs. Object keys never cross this boundary to clients. */
public interface OfficialPdfStorage {
    void checkReady();

    /** Stores a deterministic candidate key without overwriting an existing object. */
    ObjectProof putIfAbsent(java.util.UUID issuanceId, String objectKey, byte[] pdfBytes);

    /** Reads and validates the exact immutable object version before finalization. */
    ObjectProof verify(java.util.UUID issuanceId, String objectKey, String objectVersion,
                       String expectedSha256, long expectedSize);

    /** Reads bytes only after validating the stored version, size, media type, and SHA-256. */
    DownloadedPdf download(java.util.UUID issuanceId, String objectKey, String objectVersion,
                           String expectedSha256, long expectedSize);

    record ObjectProof(String objectVersion, String sha256, long size) { }
    record DownloadedPdf(byte[] bytes, String sha256, long size) { }
}
