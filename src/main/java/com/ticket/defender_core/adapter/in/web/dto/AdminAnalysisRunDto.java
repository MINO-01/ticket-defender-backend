package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ListingContextAnalysis;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Event-scoped, persisted Track 2 execution history. This is not a generic analysis-job queue. */
public final class AdminAnalysisRunDto {
    private AdminAnalysisRunDto() { }

    public record Item(UUID analysisId, UUID fanReportId, UUID evidenceId,
                       ListingContextAnalysis.Execution executionStatus,
                       ListingContextAnalysis.AnalysisStatus analysisStatus,
                       String errorCode, String analyzerErrorCode,
                       OffsetDateTime createdAt, OffsetDateTime completedAt) {
        public static Item from(ListingContextAnalysis value) {
            return new Item(value.getId(), value.getFanReportId(), value.getEvidenceId(),
                    value.getExecutionStatus(), value.getAnalysisStatus(), value.getErrorCode(),
                    value.getAnalyzerErrorCode(), utc(value.getCreatedAt()), utc(value.getCompletedAt()));
        }
    }

    public record Page(List<Item> items, int page, int pageSize, boolean hasNext,
                       String coverage) { }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
