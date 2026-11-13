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
                               String cursorStart, String cursorEnd) { }
    public record Scope(UUID eventId, UUID performanceId, OffsetDateTime scopeAsOf, long declaredRowCount,
                        String cursorStart, String cursorEnd) {
        public static Scope from(BulkLedgerImportScope value) {
            return new Scope(value.getEventId(), value.getPerformanceId(), offset(value.getScopeAsOf()),
                    value.getDeclaredRowCount(), value.getCursorStart(), value.getCursorEnd());
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
    public record Page<T>(List<T> items, int page, int pageSize, boolean hasNext) { }

    private static OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
