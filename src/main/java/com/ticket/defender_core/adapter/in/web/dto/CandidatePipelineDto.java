package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.application.service.CandidateMatchDecision;
import com.ticket.defender_core.domain.ReservationImportBatch;
import com.ticket.defender_core.domain.VerifiedPerformance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Track 2 회차 검증·합성 예매 적재·후보 대조 API DTO입니다. */
public final class CandidatePipelineDto {
    private CandidatePipelineDto() { }

    public record CreatePerformance(String performanceRef, String verificationBasis, String sourceVersion) { }
    public record RevisePerformance(String verificationBasis, String sourceVersion, int expectedRevision) { }
    public record WithdrawPerformance(String reason, int expectedRevision) { }
    public record Performance(UUID performanceId, UUID eventId, String performanceRef,
                              VerifiedPerformance.Status status, int revision, String sourceVersion,
                              LocalDateTime verifiedAt, LocalDateTime withdrawnAt) { }

    public record LinkPerformance(UUID performanceId, int expectedLinkRevision, String reason) { }
    public record WithdrawLink(int expectedLinkRevision, String reason) { }
    public record PerformanceLink(UUID linkId, UUID fanReportId, UUID performanceId, String performanceRef,
                                  int performanceRevision, int linkRevision, String status,
                                  LocalDateTime createdAt, LocalDateTime endedAt) { }
    public record PerformanceLinkHistory(List<PerformanceLink> items) { }

    /** ticketReference is optional for legacy one-seat sources; when supplied it is HMAC-protected before persistence. */
    public record ReservationRow(String reservationReference, String zone, String row, String seat, String ticketReference) {
        public ReservationRow(String reservationReference, String zone, String row, String seat) {
            this(reservationReference, zone, row, seat, null);
        }
    }
    public record ImportReservations(UUID performanceId, String sourceVersion, Boolean completeSnapshot,
                                     List<ReservationRow> rows) { }
    public record ImportError(int rowIndex, String code) { }
    public record ImportResult(UUID batchId, UUID eventId, UUID performanceId, String sourceVersion,
                               ReservationImportBatch.Status status, boolean completeSnapshot,
                               int inputRowCount, int acceptedRowCount, int rejectedRowCount,
                               List<ImportError> errors, LocalDateTime completedAt) { }

    public enum Applicability {
        CURRENT,
        PERFORMANCE_LINK_REPLACED_OR_WITHDRAWN,
        PERFORMANCE_WITHDRAWN,
        PERFORMANCE_REVISION_CHANGED,
        RESERVATION_SOURCE_SUPERSEDED,
        ANALYSIS_OR_EVIDENCE_CHANGED,
        NOT_APPLICABLE
    }

    public record CandidateRun(UUID runId, UUID analysisId, UUID fanReportId, UUID evidenceId,
                               CandidateMatchDecision.Status status, CandidateMatchDecision.Reason reason,
                               Integer candidateCount, UUID candidateId, UUID performanceLinkId,
                               Integer performanceLinkRevision, UUID verifiedPerformanceId,
                               Integer verifiedPerformanceRevision, UUID reservationBatchId,
                               String reservationSourceVersion, String evidenceSha256,
                               String analysisResponseSha256, Long analysisRowVersion,
                               Applicability applicability, LocalDateTime createdAt, LocalDateTime completedAt) { }
    public record CandidateRunPage(List<CandidateRun> items, int page, int pageSize, boolean hasNext) { }

    public record ImportHistory(java.util.List<ImportResult> items, int page, int pageSize, boolean hasNext) { }
}
