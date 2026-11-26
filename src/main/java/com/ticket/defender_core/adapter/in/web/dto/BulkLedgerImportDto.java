package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.BulkLedgerImportError;
import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class BulkLedgerImportDto {
    private BulkLedgerImportDto() { }

    public record RegisterRequest(String schemaVersion, String sourceSystem, String sourceBatchId, String mode,
                                  String format, String declaredObjectSha256, Long declaredFileBytes,
                                  Long declaredRowCount, Boolean completeSnapshot, List<ScopeRequest> scopes) { }
    public record ScopeRequest(UUID eventId, UUID performanceId, OffsetDateTime scopeAsOf, Long declaredRowCount,
                               String cursorStart, String cursorEnd, Long sourceSequenceStart, Long sourceSequenceEnd) {
        public ScopeRequest(UUID eventId, UUID performanceId, OffsetDateTime scopeAsOf, Long declaredRowCount,
                            String cursorStart, String cursorEnd) {
            this(eventId, performanceId, scopeAsOf, declaredRowCount, cursorStart, cursorEnd, null, null);
        }
    }
    public record Scope(UUID eventId, UUID performanceId, OffsetDateTime scopeAsOf, long declaredRowCount,
                        String cursorStart, String cursorEnd, Long sourceSequenceStart, Long sourceSequenceEnd) {
        public static Scope from(BulkLedgerImportScope value) {
            return new Scope(value.getEventId(), value.getPerformanceId(), offset(value.getScopeAsOf()),
                    value.getDeclaredRowCount(), value.getCursorStart(), value.getCursorEnd(), value.getSourceSequenceStart(), value.getSourceSequenceEnd());
        }
    }
    public record View(UUID importId, String schemaVersion, String sourceSystem, String sourceBatchId,
                       BulkLedgerImportJob.Mode mode, BulkLedgerImportJob.Format format, String declaredObjectSha256,
                       long declaredFileBytes, long declaredRowCount, boolean completeSnapshot,
                       BulkLedgerImportJob.Status status, long processedRowCount, long acceptedRowCount,
                       long rejectedRowCount, List<Scope> scopes, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       OffsetDateTime cancelledAt) { }
    public record Error(long sequenceNo, String code, OffsetDateTime createdAt) {
        public static Error from(BulkLedgerImportError value) {
            return new Error(value.getSequenceNo(), value.getCode(), offset(value.getCreatedAt()));
        }
    }
    /** URL is returned only once to the authorized uploader and is never persisted or listed. */
    public record UploadSession(java.net.URI uploadUrl, OffsetDateTime expiresAt) { }
    /** 업로드 원본 삭제 상태입니다. 객체 키, 버전 ID, 원본 값은 담지 않습니다. */
    public record UploadDeletionStatus(boolean v3RawIntakeAccepted, long deletedCount, long failedAfterRetriesCount, int maxAttempts) { }
    public record CompleteUploadRequest() { }
    /** Publication metadata intentionally omits ticket rows, object keys, HMACs and outbox payloads. */
    public record PublicationView(UUID publicationId, UUID importId, String sourceSystem, String sourceBatchId,
                                  OffsetDateTime publishedAt, List<PublishedScope> scopes) { }
    public record PublishedScope(UUID eventId, UUID performanceId, UUID previousVersionId, UUID versionId,
                                 long generation, long revision, boolean outboxPending) { }
    /**
     * Candidate data is a point-in-time preview only. The publication command always rechecks the current heads.
     * Nullable version fields mean that preparation has not yet produced a version for that declared import scope.
     */
    public record CandidatePreviewView(UUID importId, CandidatePreparationStatus preparationStatus,
                                       CandidateDataStatus dataStatus, String failureCode,
                                       List<CandidateScope> scopes) { }
    public enum CandidatePreparationStatus { NOT_STARTED, PREPARING, READY, REJECTED, PUBLISHED }
    public enum CandidateDataStatus { NOT_AVAILABLE, PREPARING, COMPLETE, INCOMPLETE }
    public enum CandidateHeadStatus { UNAVAILABLE, UNKNOWN, CURRENT, CHANGED }
    public record CandidateScope(UUID eventId, UUID performanceId, UUID versionId, Long generation, Long revision,
                                 UUID baseVersionId, Boolean expectedHeadKnown, UUID expectedHeadVersionId,
                                 UUID currentHeadVersionId, CandidateHeadStatus headStatus, String versionStatus) { }
    public record Page<T>(List<T> items, int page, int pageSize, boolean hasNext) { }

    private static OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
